package com.example.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.R
import com.example.data.local.LocalContactEntity
import com.example.data.local.SettingsDataStore
import com.example.data.local.Zap2Dao
import com.example.data.model.ContactEntry
import com.example.data.model.EmailLookup
import com.example.data.model.PublicProfile
import com.example.data.model.UserProfile
import com.example.data.model.UsernameLookup
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID

class UserAndContactsRepository(
    private val context: Context?,
    private val db: FirebaseFirestore,
    private val dao: Zap2Dao,
    private val settingsStore: SettingsDataStore? = null,
    private val auth: FirebaseAuth = Firebase.auth,
    private val freeStorageManager: FreeMediaStorageManager? = null
) {
    constructor(db: FirebaseFirestore, dao: Zap2Dao) : this(
        context = null,
        db = db,
        dao = dao,
        settingsStore = null
    )

    constructor(context: Context, dao: Zap2Dao) : this(
        context = context.applicationContext,
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        dao = dao,
        settingsStore = SettingsDataStore(context.applicationContext)
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("Usuário deve estar autenticado antes de acessar o Firestore.")

    suspend fun ensureUserDocuments(user: FirebaseUser): Result<Unit> {
        val uid = user.uid
        val privatePath = "users/$uid"
        val publicPath = "public_profiles/$uid"
        return try {
            val privateRef = db.collection("users").document(uid)
            val existing = privateRef.get().await()
            val displayName = user.displayName?.takeIf { it.isNotBlank() } ?: "Usuário WhatwillIdo"
            val photoUrl = user.photoUrl?.toString()?.take(1000)
            val resolvedEmail = user.email?.trim()?.lowercase(Locale.ROOT)?.take(160)
                ?: existing.getString("email")?.trim()?.lowercase(Locale.ROOT)?.take(160)

            val resolvedName = existing.getString("displayName")?.takeIf { it.isNotBlank() } ?: displayName
            val resolvedAbout = existing.getString("about")?.takeIf { it.isNotBlank() } ?: "Disponível no WhatwillIdo"
            val resolvedPhoto = existing.getString("photoUrl")?.takeIf { it.isNotBlank() } ?: photoUrl
            val resolvedBanner = existing.getString("bannerUrl")?.takeIf { it.isNotBlank() }

            // Ensure unique @username is created/claimed atomically
            val existingUsername = existing.getString("username")
                ?.let(::normalizeUsername)
                ?.takeIf(::isValidUsername)
            val resolvedUsername = ensureInitialUsernameClaimed(
                uid = uid,
                preferredUsername = existingUsername,
                displayName = resolvedName,
                email = resolvedEmail,
                photoUrl = resolvedPhoto
            )

            if (!existing.exists()) {
                val createMap = mutableMapOf<String, Any?>(
                    "uid" to uid,
                    "username" to resolvedUsername,
                    "displayName" to resolvedName.take(80),
                    "about" to resolvedAbout.take(160),
                    "phoneVerified" to false,
                    "email" to resolvedEmail,
                    "photoUrl" to resolvedPhoto,
                    "online" to true,
                    "lastSeen" to FieldValue.serverTimestamp(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null } as Map<String, Any>
                privateRef.set(createMap).await()
            } else {
                val updateMap = mutableMapOf<String, Any?>(
                    "username" to resolvedUsername,
                    "phoneVerified" to false,
                    "online" to true,
                    "lastSeen" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (existing.getString("displayName").isNullOrBlank()) {
                    updateMap["displayName"] = resolvedName.take(80)
                }
                if (existing.getString("photoUrl").isNullOrBlank() && resolvedPhoto != null) {
                    updateMap["photoUrl"] = resolvedPhoto
                }
                if (existing.getString("email").isNullOrBlank() && resolvedEmail != null) {
                    updateMap["email"] = resolvedEmail
                }
                privateRef.update(updateMap.filterValues { it != null } as Map<String, Any>).await()
            }

            val publicRef = db.collection("public_profiles").document(uid)
            val pubPayload = mutableMapOf<String, Any?>(
                "uid" to uid,
                "username" to resolvedUsername,
                "displayName" to resolvedName.take(80),
                "displayNameLower" to resolvedName.trim().lowercase(Locale.ROOT).take(80),
                "about" to resolvedAbout.take(160),
                "photoUrl" to resolvedPhoto,
                "bannerUrl" to resolvedBanner,
                "online" to true,
                "lastSeen" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>
            publicRef.set(pubPayload, SetOptions.merge()).await()

            if (!resolvedEmail.isNullOrBlank()) {
                upsertEmailLookup(
                    uid = uid,
                    emailLower = resolvedEmail,
                    displayName = resolvedName,
                    username = resolvedUsername,
                    photoUrl = resolvedPhoto
                )
            }

            settingsStore?.saveLocalProfileCache(
                displayName = resolvedName,
                username = resolvedUsername,
                about = resolvedAbout,
                photoUrl = resolvedPhoto.orEmpty(),
                bannerUrl = resolvedBanner.orEmpty()
            )

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "$privatePath / $publicPath")
            Result.failure(e)
        }
    }

    private suspend fun ensureInitialUsernameClaimed(
        uid: String,
        preferredUsername: String?,
        displayName: String,
        email: String?,
        photoUrl: String?
    ): String {
        val candidates = linkedSetOf<String>()
        if (preferredUsername != null && isValidUsername(preferredUsername)) {
            candidates += preferredUsername
        }
        val base = deriveUsernameBase(email = email, displayName = displayName, uid = uid)
        candidates += base
        val uidSuffix = uid.filter { it.isLetterOrDigit() }.takeLast(4).lowercase(Locale.ROOT).ifBlank { "1" }
        candidates += "${base.take(19)}_$uidSuffix"
        repeat(6) { idx ->
            val rnd = (1000..9999).random()
            candidates += "${base.take(18)}_${rnd + idx}"
        }

        for (candidate in candidates) {
            if (!isValidUsername(candidate)) continue
            val lookupRef = db.collection("username_lookup").document(candidate)
            val snap = lookupRef.get().await()
            if (!snap.exists()) {
                val payload = mutableMapOf<String, Any?>(
                    "uid" to uid,
                    "username" to candidate,
                    "displayName" to displayName.take(80),
                    "photoUrl" to photoUrl?.take(2048),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null } as Map<String, Any>
                val claimed = try {
                    db.runTransaction { tx ->
                        val check = tx.get(lookupRef)
                        if (!check.exists() || check.getString("uid") == uid) {
                            tx.set(lookupRef, payload)
                            true
                        } else {
                            false
                        }
                    }.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (claimed) return candidate
            } else if (snap.getString("uid") == uid) {
                return candidate
            }
        }
        throw IllegalStateException("Não foi possível reservar um @username único. Tente novamente.")
    }

    private suspend fun upsertEmailLookup(
        uid: String,
        emailLower: String,
        displayName: String,
        username: String?,
        photoUrl: String?
    ) {
        if (!EMAIL_REGEX.matches(emailLower) || emailLower.contains('/')) return
        try {
            val payload = mutableMapOf<String, Any?>(
                "uid" to uid,
                "displayName" to displayName.take(80),
                "username" to username?.takeIf(::isValidUsername),
                "photoUrl" to photoUrl?.take(2048),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>
            db.collection("email_lookup").document(emailLower).set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "email_lookup/$emailLower")
        }
    }

    fun observeMyProfile(uid: String): Flow<UserProfile?> = flow {
        val path = "users/$uid"
        emitAll(
            db.collection("users").document(uid)
                .snapshots()
                .map { snap ->
                    val profile = snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    if (profile != null) {
                        settingsStore?.saveLocalProfileCache(
                            displayName = profile.displayName,
                            username = profile.username,
                            about = profile.about,
                            photoUrl = profile.photoUrl.orEmpty(),
                            bannerUrl = profile.bannerUrl.orEmpty()
                        )
                    }
                    profile
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    fun observePublicProfile(uid: String): Flow<PublicProfile?> = flow {
        val path = "public_profiles/$uid"
        emitAll(
            db.collection("public_profiles").document(uid)
                .snapshots()
                .map { it.toObject(PublicProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser != null) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, path)
                    }
                    emit(null)
                }
        )
    }

    suspend fun checkUsernameAvailability(rawUsername: String): Result<Boolean> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado"))
        val clean = normalizeUsername(rawUsername)
        if (!isValidUsername(clean)) {
            return Result.failure(
                IllegalArgumentException("Use 3 a 24 caracteres: letras minúsculas, números, ponto (.) ou underline (_).")
            )
        }
        return try {
            val snap = db.collection("username_lookup").document(clean).get().await()
            Result.success(!snap.exists() || snap.getString("uid") == uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateProfile(
        displayName: String,
        username: String,
        about: String,
        phoneNumber: String?
    ): Result<String> {
        val user = auth.currentUser ?: return Result.failure(IllegalStateException("Não autenticado"))
        val uid = user.uid
        val cleanName = displayName.trim().take(80)
        if (cleanName.isEmpty()) {
            return Result.failure(IllegalArgumentException("Nome de exibição não pode ser vazio."))
        }
        val cleanUsername = normalizeUsername(username)
        if (!isValidUsername(cleanUsername)) {
            return Result.failure(
                IllegalArgumentException(
                    "Nome de usuário inválido. Use 3 a 24 caracteres (a-z, 0-9, '.' ou '_')."
                )
            )
        }
        val cleanAbout = about.trim().take(160)
        val cleanPhone = phoneNumber?.trim()?.take(32)?.takeIf { it.isNotEmpty() }

        return try {
            val userRef = db.collection("users").document(uid)
            val publicRef = db.collection("public_profiles").document(uid)
            val newLookupRef = db.collection("username_lookup").document(cleanUsername)

            db.runTransaction { tx ->
                val existingUser = tx.get(userRef)
                val existingPublic = tx.get(publicRef)
                val newLookupSnap = tx.get(newLookupRef)

                if (newLookupSnap.exists() && newLookupSnap.getString("uid") != uid) {
                    throw IllegalStateException("O @$cleanUsername já está em uso por outro usuário.")
                }

                val oldUsername = existingUser.getString("username")
                    ?.let(::normalizeUsername)
                    ?.takeIf(::isValidUsername)
                val oldLookupSnap = if (oldUsername != null && oldUsername != cleanUsername) {
                    tx.get(db.collection("username_lookup").document(oldUsername))
                } else {
                    null
                }

                val existingPhoto = existingUser.getString("photoUrl")?.takeIf { it.isNotBlank() }
                    ?: user.photoUrl?.toString()?.take(1000)

                val lookupPayload = mutableMapOf<String, Any?>(
                    "uid" to uid,
                    "username" to cleanUsername,
                    "displayName" to cleanName,
                    "photoUrl" to existingPhoto,
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null } as Map<String, Any>
                tx.set(newLookupRef, lookupPayload)

                if (oldUsername != null &&
                    oldUsername != cleanUsername &&
                    oldLookupSnap != null &&
                    oldLookupSnap.exists() &&
                    oldLookupSnap.getString("uid") == uid
                ) {
                    tx.delete(db.collection("username_lookup").document(oldUsername))
                }

                val userUpdates = mutableMapOf<String, Any>(
                    "username" to cleanUsername,
                    "displayName" to cleanName,
                    "about" to cleanAbout,
                    "phoneVerified" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (cleanPhone != null) {
                    userUpdates["phoneNumber"] = cleanPhone
                } else if (existingUser.contains("phoneNumber")) {
                    userUpdates["phoneNumber"] = FieldValue.delete()
                }
                tx.update(userRef, userUpdates)

                if (existingPublic.exists()) {
                    tx.update(
                        publicRef,
                        mapOf(
                            "username" to cleanUsername,
                            "displayName" to cleanName,
                            "displayNameLower" to cleanName.lowercase(Locale.ROOT),
                            "about" to cleanAbout,
                            "online" to true,
                            "lastSeen" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                } else {
                    val createPub = mutableMapOf<String, Any?>(
                        "uid" to uid,
                        "username" to cleanUsername,
                        "displayName" to cleanName,
                        "displayNameLower" to cleanName.lowercase(Locale.ROOT),
                        "about" to cleanAbout,
                        "photoUrl" to existingPhoto,
                        "online" to true,
                        "lastSeen" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    ).filterValues { it != null } as Map<String, Any>
                    tx.set(publicRef, createPub)
                }
            }.await()

            val emailLower = user.email?.trim()?.lowercase(Locale.ROOT)
            if (!emailLower.isNullOrBlank()) {
                val userSnap = userRef.get().await()
                upsertEmailLookup(
                    uid = uid,
                    emailLower = emailLower,
                    displayName = cleanName,
                    username = cleanUsername,
                    photoUrl = userSnap.getString("photoUrl")
                )
            }

            settingsStore?.saveLocalProfileCache(
                displayName = cleanName,
                username = cleanUsername,
                about = cleanAbout
            )
            Result.success(cleanUsername)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/$uid")
            Result.failure(e)
        }
    }

    suspend fun updateProfileBanner(
        bannerUrl: String? = null,
        localImageUri: Uri? = null,
        isVideo: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): Result<String> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado"))
        return try {
            val finalUrl = when {
                localImageUri != null -> {
                    val ctx = context ?: return Result.failure(IllegalStateException("Contexto indisponível."))
                    val (localFile, mime) = prepareProfileMediaFile(ctx, localImageUri, "banner_$uid", isVideo = isVideo, maxDim = 1600)
                    uploadProfileMediaFree(
                        ownerId = uid,
                        file = localFile,
                        mimeType = mime,
                        onProgress = onProgress
                    )
                }
                else -> validateHttpUrlOrEmpty(bannerUrl)
            }

            db.collection("users").document(uid).update(
                mapOf(
                    "bannerUrl" to finalUrl,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            db.collection("public_profiles").document(uid).set(
                mapOf(
                    "uid" to uid,
                    "bannerUrl" to finalUrl,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()

            settingsStore?.saveLocalProfileCache(bannerUrl = finalUrl)
            Result.success(finalUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/$uid")
            Result.failure(e)
        }
    }

    suspend fun updateProfileAvatar(
        avatarUrl: String? = null,
        localMediaUri: Uri? = null,
        isVideo: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): Result<String> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado"))
        return try {
            val finalUrl = when {
                localMediaUri != null -> {
                    val ctx = context ?: return Result.failure(IllegalStateException("Contexto indisponível."))
                    val (localFile, mime) = prepareProfileMediaFile(ctx, localMediaUri, "avatar_$uid", isVideo = isVideo, maxDim = 720)
                    uploadProfileMediaFree(
                        ownerId = uid,
                        file = localFile,
                        mimeType = mime,
                        onProgress = onProgress
                    )
                }
                else -> validateHttpUrlOrEmpty(avatarUrl)
            }

            db.collection("users").document(uid).update(
                mapOf(
                    "photoUrl" to finalUrl,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            db.collection("public_profiles").document(uid).set(
                mapOf(
                    "uid" to uid,
                    "photoUrl" to finalUrl,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()

            val userSnap = db.collection("users").document(uid).get().await()
            val name = userSnap.getString("displayName") ?: "Usuário WhatwillIdo"
            val username = userSnap.getString("username")?.let(::normalizeUsername)?.takeIf(::isValidUsername)
            if (username != null) {
                runCatching {
                    db.collection("username_lookup").document(username).update(
                        mapOf(
                            "photoUrl" to finalUrl,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            }
            val emailLower = auth.currentUser?.email?.trim()?.lowercase(Locale.ROOT)
            if (!emailLower.isNullOrBlank()) {
                upsertEmailLookup(
                    uid = uid,
                    emailLower = emailLower,
                    displayName = name,
                    username = username,
                    photoUrl = finalUrl.takeIf { it.isNotBlank() }
                )
            }

            settingsStore?.saveLocalProfileCache(photoUrl = finalUrl)
            Result.success(finalUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/$uid")
            Result.failure(e)
        }
    }

    private fun validateHttpUrlOrEmpty(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val parsed = Uri.parse(trimmed)
        val scheme = parsed.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") {
            "Informe uma URL válida começando com https:// ou http://"
        }
        require(trimmed.length <= 2048) { "A URL é muito longa (máximo 2048 caracteres)." }
        return trimmed
    }

    private suspend fun prepareProfileMediaFile(
        ctx: Context,
        uri: Uri,
        baseName: String,
        isVideo: Boolean,
        maxDim: Int
    ): Pair<File, String> = withContext(Dispatchers.IO) {
        val mediaDir = File(ctx.filesDir, "media").apply { mkdirs() }
        val mime = ctx.contentResolver.getType(uri)?.lowercase() ?: if (isVideo) "video/mp4" else "image/jpeg"

        if (isVideo || mime.startsWith("video/") || mime == "image/gif") {
            val ext = when {
                mime == "image/gif" -> "gif"
                mime.contains("webm") -> "webm"
                else -> "mp4"
            }
            val dest = File(mediaDir, "profile_${baseName}.$ext")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                    }
                }
            } ?: throw IllegalStateException("Não foi possível ler o arquivo selecionado.")
            return@withContext dest to mime
        }

        val rawTemp = File(ctx.cacheDir, "tmp_profile_${UUID.randomUUID()}.bin")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                rawTemp.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Não foi possível ler a imagem selecionada.")

            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(rawTemp.absolutePath, boundsOpts)
            var sampleSize = 1
            while (boundsOpts.outWidth / sampleSize > maxDim * 2 || boundsOpts.outHeight / sampleSize > maxDim * 2) {
                sampleSize *= 2
            }
            val decoded = BitmapFactory.decodeFile(
                rawTemp.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            ) ?: throw IllegalStateException("Arquivo selecionado não é uma imagem válida.")

            val scaled = if (decoded.width > maxDim || decoded.height > maxDim) {
                val ratio = minOf(maxDim.toFloat() / decoded.width, maxDim.toFloat() / decoded.height)
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * ratio).toInt().coerceAtLeast(1),
                    (decoded.height * ratio).toInt().coerceAtLeast(1),
                    true
                ).also { if (it !== decoded) decoded.recycle() }
            } else {
                decoded
            }

            val dest = File(mediaDir, "profile_${baseName}.jpg")
            try {
                ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
                    dest.writeBytes(out.toByteArray())
                }
            } finally {
                scaled.recycle()
            }
            dest to "image/jpeg"
        } finally {
            rawTemp.delete()
        }
    }

    private suspend fun uploadProfileMediaFree(
        ownerId: String,
        file: File,
        mimeType: String,
        onProgress: (Float) -> Unit
    ): String {
        val ctx = context ?: throw IllegalStateException("Contexto indisponível.")
        val manager = freeStorageManager
            ?: FreeMediaStorageManager(ctx, db, settingsStore ?: SettingsDataStore(ctx))
        return manager.uploadPublicProfileMedia(
            ownerId = ownerId,
            file = file,
            mimeType = mimeType,
            onProgress = onProgress
        )
    }

    suspend fun setOnlineStatus(online: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        try {
            val payload = mapOf(
                "online" to online,
                "lastSeen" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("public_profiles").document(uid).update(payload).await()
            db.collection("users").document(uid).update(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "public_profiles/$uid")
        }
    }

    suspend fun saveFcmToken(token: String) {
        val uid = auth.currentUser?.uid ?: return
        if (token.isBlank()) return
        try {
            db.collection("users").document(uid).update(
                mapOf(
                    "fcmToken" to token.take(500),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/$uid")
        }
    }

    fun observeSavedContacts(uid: String): Flow<List<ContactEntry>> = flow {
        val path = "users/$uid/contacts"
        emitAll(
            db.collection("users").document(uid).collection("contacts")
                .snapshots()
                .map { snapshot ->
                    val list = snapshot.toObjects(
                        ContactEntry::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.displayName.lowercase() }
                    dao.upsertContacts(
                        list.map { c ->
                            LocalContactEntity(
                                contactId = c.id.ifBlank { c.peerUid },
                                ownerId = c.ownerId,
                                peerUid = c.peerUid,
                                username = c.username,
                                displayName = c.displayName,
                                phoneNumber = c.phoneNumber,
                                photoUrl = c.photoUrl,
                                updatedAtMs = c.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
                            )
                        }
                    )
                    list
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    /**
     * Searches Zap2 users securely by:
     * 1. Exact Google email (`email_lookup/{emailLower}`, no collection listing allowed)
     * 2. `@username` exact match (`username_lookup/{handle}`) + prefix query on `public_profiles.username`
     * 3. Display name prefix query on `public_profiles.displayNameLower` (and fallback `displayName`)
     * 4. Direct Firebase UID match on `public_profiles/{uid}`
     */
    suspend fun searchUsers(rawQuery: String): Result<List<PublicProfile>> {
        val trimmed = rawQuery.trim()
        if (trimmed.isBlank()) {
            return Result.success(emptyList())
        }
        val lower = trimmed.lowercase(Locale.ROOT)
        val cleanHandle = normalizeUsername(trimmed)
        val results = linkedMapOf<String, PublicProfile>()

        return try {
            // 1. Exact email lookup
            if (EMAIL_REGEX.matches(lower) && !lower.contains('/')) {
                val emailSnap = runCatching {
                    db.collection("email_lookup").document(lower).get().await()
                }.getOrNull()
                if (emailSnap != null && emailSnap.exists()) {
                    val lookup = emailSnap.toObject(EmailLookup::class.java)
                    if (lookup != null && lookup.uid.isNotBlank()) {
                        val profile = fetchPublicProfileOrFallback(
                            uid = lookup.uid,
                            fallbackName = lookup.displayName,
                            fallbackUsername = lookup.username,
                            fallbackPhoto = lookup.photoUrl
                        )
                        results[profile.uid] = profile
                    }
                }
            }

            // 2. Exact @username lookup
            if (isValidUsername(cleanHandle)) {
                val usernameSnap = runCatching {
                    db.collection("username_lookup").document(cleanHandle).get().await()
                }.getOrNull()
                if (usernameSnap != null && usernameSnap.exists()) {
                    val lookup = usernameSnap.toObject(UsernameLookup::class.java)
                    if (lookup != null && lookup.uid.isNotBlank() && !results.containsKey(lookup.uid)) {
                        val profile = fetchPublicProfileOrFallback(
                            uid = lookup.uid,
                            fallbackName = lookup.displayName,
                            fallbackUsername = lookup.username,
                            fallbackPhoto = lookup.photoUrl
                        )
                        results[profile.uid] = profile
                    }
                }
            }

            // 3. @username prefix search on public_profiles
            val handlePrefix = trimmed.trimStart('@').lowercase(Locale.ROOT)
                .filter { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' }
            if (handlePrefix.isNotBlank()) {
                val usernameQuerySnap = runCatching {
                    db.collection("public_profiles")
                        .whereGreaterThanOrEqualTo("username", handlePrefix)
                        .whereLessThanOrEqualTo("username", "$handlePrefix\uf8ff")
                        .limit(15)
                        .get()
                        .await()
                }.getOrNull()
                usernameQuerySnap?.documents?.forEach { doc ->
                    val p = doc.toObject(
                        PublicProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                    if (p != null && p.uid.isNotBlank() && !results.containsKey(p.uid)) {
                        results[p.uid] = p
                    }
                }
            }

            // 4. Display name search (lowercase prefix + case-preserved fallback for legacy profiles)
            if (!trimmed.startsWith("@")) {
                val nameQueryLowerSnap = runCatching {
                    db.collection("public_profiles")
                        .whereGreaterThanOrEqualTo("displayNameLower", lower)
                        .whereLessThanOrEqualTo("displayNameLower", "$lower\uf8ff")
                        .limit(15)
                        .get()
                        .await()
                }.getOrNull()
                nameQueryLowerSnap?.documents?.forEach { doc ->
                    val p = doc.toObject(
                        PublicProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                    if (p != null && p.uid.isNotBlank() && !results.containsKey(p.uid)) {
                        results[p.uid] = p
                    }
                }

                val nameQueryExactCaseSnap = runCatching {
                    db.collection("public_profiles")
                        .whereGreaterThanOrEqualTo("displayName", trimmed)
                        .whereLessThanOrEqualTo("displayName", "$trimmed\uf8ff")
                        .limit(10)
                        .get()
                        .await()
                }.getOrNull()
                nameQueryExactCaseSnap?.documents?.forEach { doc ->
                    val p = doc.toObject(
                        PublicProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                    if (p != null && p.uid.isNotBlank() && !results.containsKey(p.uid)) {
                        results[p.uid] = p
                    }
                }

                // 5. Direct UID fallback
                if (trimmed.length >= 6 && UID_REGEX.matches(trimmed) && !results.containsKey(trimmed)) {
                    val directSnap = runCatching {
                        db.collection("public_profiles").document(trimmed).get().await()
                    }.getOrNull()
                    if (directSnap != null && directSnap.exists()) {
                        val p = directSnap.toObject(
                            PublicProfile::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                        if (p != null && p.uid.isNotBlank()) {
                            results[p.uid] = p
                        }
                    }
                }
            }

            Result.success(results.values.toList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, "public_profiles / username_lookup / email_lookup")
            Result.failure(e)
        }
    }

    private suspend fun fetchPublicProfileOrFallback(
        uid: String,
        fallbackName: String,
        fallbackUsername: String,
        fallbackPhoto: String?
    ): PublicProfile {
        val snap = runCatching {
            db.collection("public_profiles").document(uid).get().await()
        }.getOrNull()
        return snap?.toObject(
            PublicProfile::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
        ) ?: PublicProfile(
            id = uid,
            uid = uid,
            username = fallbackUsername,
            displayName = fallbackName,
            displayNameLower = fallbackName.lowercase(Locale.ROOT),
            photoUrl = fallbackPhoto
        )
    }

    suspend fun addContact(
        peerUid: String,
        displayName: String,
        username: String? = null,
        phoneNumber: String? = null,
        photoUrl: String? = null
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val cleanPeerUid = peerUid.trim().take(128)
        val cleanName = displayName.trim().take(80).ifBlank { "Contato WhatwillIdo" }
        val cleanUsername = username?.let(::normalizeUsername)?.takeIf { it.isNotBlank() }?.take(30)
        val cleanPhone = phoneNumber?.trim()?.take(32)?.takeIf { it.isNotEmpty() }
        val cleanPhoto = photoUrl?.take(1000)?.takeIf { it.isNotEmpty() }
        val docPath = "users/$uid/contacts/$cleanPeerUid"

        return try {
            val docRef = db.collection("users").document(uid)
                .collection("contacts").document(cleanPeerUid)
            val existingContact = docRef.get().await()

            if (!existingContact.exists()) {
                val createPayload = mutableMapOf<String, Any?>(
                    "ownerId" to uid,
                    "peerUid" to cleanPeerUid,
                    "username" to cleanUsername,
                    "displayName" to cleanName,
                    "phoneNumber" to cleanPhone,
                    "photoUrl" to cleanPhoto,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null } as Map<String, Any>
                docRef.set(createPayload).await()
            } else {
                val updatePayload = mutableMapOf<String, Any?>(
                    "peerUid" to cleanPeerUid,
                    "username" to cleanUsername,
                    "displayName" to cleanName,
                    "phoneNumber" to cleanPhone,
                    "photoUrl" to cleanPhoto,
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null } as Map<String, Any>
                docRef.update(updatePayload).await()
            }

            dao.upsertContacts(
                listOf(
                    LocalContactEntity(
                        contactId = cleanPeerUid,
                        ownerId = uid,
                        peerUid = cleanPeerUid,
                        username = cleanUsername,
                        displayName = cleanName,
                        phoneNumber = cleanPhone,
                        photoUrl = cleanPhoto,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteContact(contactId: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado"))
        val docPath = "users/$uid/contacts/$contactId"
        return try {
            db.collection("users").document(uid).collection("contacts").document(contactId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    companion object {
        private val USERNAME_REGEX = Regex("^[a-z0-9._]{3,24}$")
        private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        private val UID_REGEX = Regex("^[a-zA-Z0-9_\\-]+$")

        fun normalizeUsername(raw: String): String =
            raw.trim()
                .trimStart('@')
                .lowercase(Locale.ROOT)
                .replace(' ', '_')
                .filter { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' }
                .take(24)

        fun isValidUsername(normalized: String): Boolean = USERNAME_REGEX.matches(normalized)

        private fun deriveUsernameBase(email: String?, displayName: String, uid: String): String {
            val fromEmail = email?.substringBefore('@')?.let(::normalizeUsername).orEmpty()
            val fromName = normalizeUsername(displayName)
            val chosen = when {
                fromEmail.length >= 3 -> fromEmail
                fromName.length >= 3 -> fromName
                else -> "user_${uid.take(6).lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }}"
            }.trim('.', '_')
            return if (chosen.length >= 3) {
                chosen.take(20)
            } else {
                ("zap$chosen${uid.take(4).lowercase(Locale.ROOT)}")
                    .filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
                    .take(20)
                    .padEnd(3, '0')
            }
        }
    }
}
