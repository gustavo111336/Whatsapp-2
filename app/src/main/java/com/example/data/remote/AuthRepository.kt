package com.example.data.remote

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

sealed interface GoogleSignInOutcome {
    data class Success(val user: FirebaseUser) : GoogleSignInOutcome
    data object Cancelled : GoogleSignInOutcome
    data class Error(val message: String) : GoogleSignInOutcome
}

sealed interface PhoneVerificationEvent {
    data class CodeSent(
        val verificationId: String,
        val resendToken: PhoneAuthProvider.ForceResendingToken
    ) : PhoneVerificationEvent
    data class AutoVerified(val credential: PhoneAuthCredential) : PhoneVerificationEvent
    data class Failed(val message: String) : PhoneVerificationEvent
}

class AuthRepository(
    private val auth: FirebaseAuth = Firebase.auth
) {
    fun observeAuthState(): Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser)
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    fun currentUser(): FirebaseUser? = auth.currentUser

    suspend fun attemptAutoSignIn(context: Context): Boolean {
        if (auth.currentUser != null) return true
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            return false
        }
        if (clientId.isBlank()) return false

        val credentialManager = CredentialManager.create(context)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            val result = credentialManager.getCredential(context, request)
            val cred = result.credential
            if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
                auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
                true
            } else {
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Silent auto sign-in unavailable: ${e.message}")
            false
        }
    }

    suspend fun signInWithGoogleInteractive(activity: Activity): GoogleSignInOutcome {
        val clientId = try {
            activity.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            return GoogleSignInOutcome.Error("Configuração Google Sign-In ausente (default_web_client_id).")
        }
        if (clientId.isBlank()) {
            return GoogleSignInOutcome.Error("Web Client ID não encontrado em google-services.json.")
        }

        val credentialManager = CredentialManager.create(activity)
        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        return try {
            val result = credentialManager.getCredential(activity, request)
            val cred = result.credential
            if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
                val authResult = auth.signInWithCredential(
                    GoogleAuthProvider.getCredential(idToken, null)
                ).await()
                val user = authResult.user
                    ?: return GoogleSignInOutcome.Error("Usuário autenticado indisponível.")
                GoogleSignInOutcome.Success(user)
            } else {
                GoogleSignInOutcome.Error("Tipo de credencial inesperado.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Google Sign-In cancelled or dismissed: ${e.message}", e)
            GoogleSignInOutcome.Cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign-In failed", e)
            GoogleSignInOutcome.Error(e.localizedMessage ?: "Falha ao entrar com Google.")
        }
    }

    fun startPhoneNumberVerification(
        activity: Activity,
        phoneE164: String,
        resendToken: PhoneAuthProvider.ForceResendingToken? = null,
        onEvent: (PhoneVerificationEvent) -> Unit
    ) {
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                onEvent(PhoneVerificationEvent.AutoVerified(credential))
            }

            override fun onVerificationFailed(e: FirebaseException) {
                val errorCode = (e as? FirebaseAuthException)?.errorCode
                Log.w(TAG, "Phone verification failed [$errorCode]: ${e.message}", e)
                val rawMessage = e.localizedMessage ?: e.message.orEmpty()
                val isOperationNotAllowed = errorCode == "ERROR_OPERATION_NOT_ALLOWED" ||
                    rawMessage.contains("not allowed", ignoreCase = true) ||
                    rawMessage.contains("não é permitida", ignoreCase = true)
                val friendlyMessage = if (isOperationNotAllowed) {
                    "Falha no SMS ($errorCode: ${rawMessage.ifBlank { "operação não permitida" }}). " +
                        "Verifique no Console do Firebase: 1) Telefone ativado em Sign-in method (ou cadastre um número de teste), " +
                        "2) Política de região SMS (+55) / plano Blaze, 3) SHA-1 e SHA-256 no app Android. " +
                        "Ou toque em 'Salvar Perfil' para registrar o número sem SMS."
                } else {
                    if (errorCode != null) "[$errorCode] $rawMessage" else rawMessage.ifBlank {
                        "Falha ao enviar SMS. Verifique a configuração no Console do Firebase."
                    }
                }
                onEvent(PhoneVerificationEvent.Failed(friendlyMessage))
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                onEvent(PhoneVerificationEvent.CodeSent(verificationId, token))
            }
        }

        val optionsBuilder = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneE164)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)

        if (resendToken != null) {
            optionsBuilder.setForceResendingToken(resendToken)
        }
        PhoneAuthProvider.verifyPhoneNumber(optionsBuilder.build())
    }

    suspend fun verifySmsCodeAndLink(
        verificationId: String,
        smsCode: String
    ): Result<String> {
        return try {
            val credential = PhoneAuthProvider.getCredential(verificationId, smsCode.trim())
            linkOrUpdatePhoneCredential(credential)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun linkOrUpdatePhoneCredential(credential: PhoneAuthCredential): Result<String> {
        return try {
            val user = auth.currentUser
                ?: return Result.failure(IllegalStateException("É necessário estar conectado com Google primeiro."))
            val result = try {
                user.linkWithCredential(credential).await()
            } catch (e: Exception) {
                if (e.message?.contains("already linked", ignoreCase = true) == true ||
                    e.message?.contains("PROVIDER_ALREADY_LINKED", ignoreCase = true) == true
                ) {
                    user.updatePhoneNumber(credential).await()
                    null
                } else {
                    throw e
                }
            }
            val linkedPhone = result?.user?.phoneNumber ?: auth.currentUser?.phoneNumber ?: ""
            Result.success(linkedPhone)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signOut(context: Context) {
        val credentialManager = CredentialManager.create(context.applicationContext)
        withContext(NonCancellable) {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear credential state: ${e.message}")
            } finally {
                auth.signOut()
            }
        }
    }

    private companion object {
        const val TAG = "AuthRepository"
    }
}
