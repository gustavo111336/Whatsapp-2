package com.example.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import com.example.R
import com.example.data.local.LocalContactEntity
import com.example.data.local.SettingsDataStore
import com.example.data.local.Zap2Dao
import com.example.data.model.ContactEntry
import com.example.data.model.DeviceContact
import com.example.data.model.PhoneLookup
import com.example.data.model.PublicProfile
import com.example.data.model.UserProfile
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
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.storage
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
import java.util.UUID

class UserAndContactsRepository(
    private val context: Context?,
    private val db: FirebaseFirestore,
    private val dao: Zap2Dao,
    private val settingsStore: SettingsDataStore? = null,
    private val auth: FirebaseAuth = Firebase.auth,
    private val storage: FirebaseStorage = Firebase.storage
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
            val displayName = user.displayName?.takeIf { it.isNotBlank() } ?: "Usuário Zap2"
            val photoUrl = user.photoUrl?.toString()?.take(1000)

            if (!existing.exists()) {
                val createMap = mutableMapOf<String, Any?>(
                    "uid" to uid,
                    "displayName" to displayName.take(80),
                    "about" to "Disponível no Zap2",
                    "phoneNumber" to user.phoneNumber?.take(32),
                    "phoneVerified" to !user.phoneNumber.isNullOrBlank(),
                    "email" to user.email?.take(160),
                    "photoUrl" to photoUrl,
                    "online" to true,
                    "lastSeen" to FieldValue.serverTimestamp(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null } as Map<String, Any>
                privateRef.set(createMap).await()
            } else {
                val updateMap = mutableMapOf<String, Any?>(
                    "online" to true,
                    "lastSeen" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (existing.getString("displayName").isNullOrBlank()) {
                    updateMap["displayName"] = displayName.take(80)
                }
                if (existing.getString("photoUrl").isNullOrBlank() && photoUrl != null) {
                    updateMap["photoUrl"] = photoUrl
                }
                privateRef.update(updateMap.filterValues { it != null } as Map<String, Any>).await()
            }

            val publicRef = db.collection("public_profiles").document(uid)
            val existingPub = publicRef.get().await()
            val resolvedName = existing.getString("displayName")?.takeIf { it.isNotBlank() } ?: displayName
            val resolvedAbout = existing.getString("about") ?: "Disponível no Zap2"
            val resolvedPhoto = existing.getString("photoUrl") ?: photoUrl

            if (!existingPub.exists()) {
                val pubCreate = mutableMapOf<String, Any?>(
                    "uid" to uid,
                    "displayName" to resolvedName.take(80),
                    "about" to resolvedAbout.take(160),
                    "photoUrl" to resolvedPhoto,
                    "online" to true,
                    "lastSeen" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null } as Map<String, Any>
                publicRef.set(pubCreate).await()
            } else {
                publicRef.update(
                    mapOf(
                        "online" to true,
                        "lastSeen" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            val phoneDigits = normalizePhoneDigits(
                existing.getString("phoneNumber") ?: user.phoneNumber.orEmpty()
            )
            if (phoneDigits.length in 6..20) {
                try {
                    upsertPhoneLookup(uid, resolvedName, resolvedPhoto, phoneDigits)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    handleFirestoreError(e, OperationType.WRITE, "phone_lookup/$phoneDigits")
                }
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "$privatePath / $publicPath")
            Result.failure(e)
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

    suspend fun updateProfile(
        displayName: String,
        about: String,
        phoneNumber: String?,
        phoneVerified: Boolean
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado"))
        val cleanName = displayName.trim().take(80)
        require(cleanName.isNotEmpty()) { "Nome não pode ser vazio." }
        val cleanAbout = about.trim().take(160)
        val cleanPhone = phoneNumber?.trim()?.take(32)?.takeIf { it.isNotEmpty() }

        return try {
            val updates = mutableMapOf<String, Any?>(
                "displayName" to cleanName,
                "about" to cleanAbout,
                "phoneVerified" to phoneVerified,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (cleanPhone != null) {
                updates["phoneNumber"] = cleanPhone
            }
            db.collection("users").document(uid)
                .update(updates.filterValues { it != null } as Map<String, Any>)
                .await()

            db.collection("public_profiles").document(uid)
                .set(
                    mapOf(
                        "uid" to uid,
                        "displayName" to cleanName,
                        "about" to cleanAbout,
                        "online" to true,
                        "lastSeen" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
                .await()

            settingsStore?.saveLocalProfileCache(displayName = cleanName, about = cleanAbout)

            if (cleanPhone != null) {
                val digits = normalizePhoneDigits(cleanPhone)
                if (digits.length in 6..20) {
                    val userSnap = db.collection("users").document(uid).get().await()
                    val currentPhoto = userSnap.getString("photoUrl") ?: auth.currentUser?.photoUrl?.toString()
                    upsertPhoneLookup(uid, cleanName, currentPhoto, digits)
                }
            }
            Result.success(Unit)
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
        onProgress: (Float) -> Unit = {}
    ): Result<String> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Não autenticado"))
        return try {
            val finalUrl = when {
                localImageUri != null -> {
                    val ctx = context ?: return Result.failure(IllegalStateException("Contexto indisponível."))
                    val (localFile, mime) = prepareProfileMediaFile(ctx, localImageUri, "banner_$uid", isVideo = false, maxDim = 1600)
                    uploadProfileMediaToStorage(
                        storagePath = "users/$uid/profile/banner_${System.currentTimeMillis()}.${localFile.extension}",
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
                    uploadProfileMediaToStorage(
                        storagePath = "users/$uid/profile/avatar_${System.currentTimeMillis()}.${localFile.extension}",
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
            val name = userSnap.getString("displayName") ?: "Usuário Zap2"
            val phone = userSnap.getString("phoneNumber").orEmpty()
            val digits = normalizePhoneDigits(phone)
            if (digits.length in 6..20) {
                upsertPhoneLookup(uid, name, finalUrl.takeIf { it.isNotBlank() }, digits)
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

        // Static image: scale down nicely and preserve high quality
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

    private suspend fun uploadProfileMediaToStorage(
        storagePath: String,
        file: File,
        mimeType: String,
        onProgress: (Float) -> Unit
    ): String {
        storage.maxUploadRetryTimeMillis = 120_000L
        val metadata = StorageMetadata.Builder()
            .setContentType(mimeType)
            .build()
        val task = storage.reference.child(storagePath).putFile(Uri.fromFile(file), metadata)
        task.addOnProgressListener { snap ->
            val total = snap.totalByteCount
            if (total > 0L) {
                onProgress((snap.bytesTransferred.toFloat() / total.toFloat()).coerceIn(0f, 1f))
            }
        }
        return try {
            task.await()
            task.snapshot.storage.downloadUrl.await().toString()
        } catch (e: StorageException) {
            val friendly = when (e.errorCode) {
                StorageException.ERROR_BUCKET_NOT_FOUND,
                StorageException.ERROR_OBJECT_NOT_FOUND ->
                    "Firebase Storage não está ativo neste projeto. Ative o Storage no Console Firebase ou cole uma URL direta (https://...)."
                StorageException.ERROR_NOT_AUTHORIZED,
                StorageException.ERROR_NOT_AUTHENTICATED ->
                    "Sem permissão para enviar ao Firebase Storage. Verifique as regras do Storage."
                else -> "Falha no upload para o Storage: ${e.localizedMessage ?: "tente novamente."}"
            }
            throw IllegalStateException(friendly, e)
        }
    }

    private suspend fun upsertPhoneLookup(
        uid: String,
        displayName: String,
        photoUrl: String?,
        digits: String
    ) {
        if (digits.length !in 6..20) return
        val docRef = db.collection("phone_lookup").document(digits)
        val payload = mutableMapOf<String, Any?>(
            "uid" to uid,
            "displayName" to displayName.take(80),
            "photoUrl" to photoUrl?.take(1000),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
        docRef.set(payload).await()
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

    suspend fun findUserByPhoneOrId(query: String): Result<PublicProfile> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Digite um número de telefone ou ID do Zap2."))
        }
        val digits = normalizePhoneDigits(trimmed)
        return try {
            // 1. Try phone_lookup if query contains at least 6 digits
            if (digits.length in 6..20) {
                val candidates = buildPhoneDigitCandidates(digits)
                for (candidate in candidates) {
                    val lookupSnap = db.collection("phone_lookup").document(candidate).get().await()
                    if (lookupSnap.exists()) {
                        val lookup = lookupSnap.toObject(PhoneLookup::class.java)
                        if (lookup != null && lookup.uid.isNotBlank()) {
                            val profileSnap = db.collection("public_profiles").document(lookup.uid).get().await()
                            val profile = profileSnap.toObject(
                                PublicProfile::class.java,
                                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                            ) ?: PublicProfile(
                                id = lookup.uid,
                                uid = lookup.uid,
                                displayName = lookup.displayName,
                                photoUrl = lookup.photoUrl
                            )
                            return Result.success(profile)
                        }
                    }
                }
            }

            // 2. Try direct UID lookup in public_profiles
            val cleanId = trimmed.replace(Regex("[^a-zA-Z0-9_\\-]"), "")
            if (cleanId.isNotBlank()) {
                val profileSnap = db.collection("public_profiles").document(cleanId).get().await()
                if (profileSnap.exists()) {
                    val profile = profileSnap.toObject(
                        PublicProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                    if (profile != null) {
                        return Result.success(profile)
                    }
                }
            }

            Result.failure(NoSuchElementException("Nenhum usuário Zap2 encontrado para \"$trimmed\"."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, "phone_lookup / public_profiles")
            Result.failure(e)
        }
    }

    suspend fun addContact(
        peerUid: String,
        displayName: String,
        phoneNumber: String? = null,
        photoUrl: String? = null
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val cleanPeerUid = peerUid.trim().take(128)
        val cleanName = displayName.trim().take(80).ifBlank { "Contato Zap2" }
        val docPath = "users/$uid/contacts/$cleanPeerUid"

        return try {
            val docRef = db.collection("users").document(uid)
                .collection("contacts").document(cleanPeerUid)
            val existingContact = docRef.get().await()

            if (!existingContact.exists()) {
                val createPayload = mutableMapOf<String, Any?>(
                    "ownerId" to uid,
                    "peerUid" to cleanPeerUid,
                    "displayName" to cleanName,
                    "phoneNumber" to phoneNumber?.trim()?.take(32)?.takeIf { it.isNotEmpty() },
                    "photoUrl" to photoUrl?.take(1000)?.takeIf { it.isNotEmpty() },
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null } as Map<String, Any>
                docRef.set(createPayload).await()
            } else {
                val updatePayload = mutableMapOf<String, Any?>(
                    "peerUid" to cleanPeerUid,
                    "displayName" to cleanName,
                    "phoneNumber" to phoneNumber?.trim()?.take(32)?.takeIf { it.isNotEmpty() },
                    "photoUrl" to photoUrl?.take(1000)?.takeIf { it.isNotEmpty() },
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
                        displayName = cleanName,
                        phoneNumber = phoneNumber,
                        photoUrl = photoUrl,
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

    suspend fun readAndMatchDeviceContacts(context: Context): Result<List<DeviceContact>> =
        withContext(Dispatchers.IO) {
            try {
                val results = mutableListOf<DeviceContact>()
                val seenPhones = HashSet<String>()

                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)

                    while (cursor.moveToNext() && results.size < 100) {
                        val id = cursor.getString(idIdx) ?: continue
                        val name = cursor.getString(nameIdx)?.trim() ?: continue
                        val rawNumber = cursor.getString(numIdx)?.trim() ?: continue
                        val digits = normalizePhoneDigits(rawNumber)
                        if (digits.length < 6 || !seenPhones.add(digits)) continue

                        var registeredUid: String? = null
                        var registeredName: String? = null
                        var registeredPhoto: String? = null

                        for (candidate in buildPhoneDigitCandidates(digits)) {
                            val snap = try {
                                db.collection("phone_lookup").document(candidate).get().await()
                            } catch (_: Exception) {
                                null
                            }
                            if (snap != null && snap.exists()) {
                                val lookup = snap.toObject(PhoneLookup::class.java)
                                if (lookup != null && lookup.uid.isNotBlank()) {
                                    registeredUid = lookup.uid
                                    registeredName = lookup.displayName
                                    registeredPhoto = lookup.photoUrl
                                    break
                                }
                            }
                        }

                        results += DeviceContact(
                            lookupId = id,
                            name = name,
                            rawPhone = rawNumber,
                            normalizedDigits = digits,
                            registeredUid = registeredUid,
                            registeredDisplayName = registeredName,
                            registeredPhotoUrl = registeredPhoto
                        )
                    }
                }
                Result.success(results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    companion object {
        fun normalizePhoneDigits(phone: String): String =
            phone.filter { it.isDigit() }.take(20)

        private fun buildPhoneDigitCandidates(digits: String): List<String> {
            val list = linkedSetOf(digits)
            if (!digits.startsWith("55") && digits.length in 10..11) {
                list.add("55$digits")
            }
            if (digits.startsWith("55") && digits.length > 11) {
                list.add(digits.removePrefix("55"))
            }
            return list.filter { it.length in 6..20 }
        }
    }
}
