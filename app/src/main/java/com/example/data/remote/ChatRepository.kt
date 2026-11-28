package com.example.data.remote

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import com.example.R
import com.example.data.local.LocalChatEntity
import com.example.data.local.LocalMessageEntity
import com.example.data.local.SavedStickerGifEntity
import com.example.data.local.ScheduledMessageEntity
import com.example.data.local.SettingsDataStore
import com.example.data.local.Zap2Dao
import com.example.data.model.ChatMessage
import com.example.data.model.ChatRoom
import com.example.data.model.PublicProfile
import com.example.data.model.TypingStatus
import com.example.data.model.UploadProgressState
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class ChatRepository(
    private val context: Context,
    private val db: FirebaseFirestore,
    private val dao: Zap2Dao,
    private val auth: FirebaseAuth = Firebase.auth,
    private val settingsStore: SettingsDataStore = SettingsDataStore(context.applicationContext),
    private val freeStorageManager: FreeMediaStorageManager = FreeMediaStorageManager(
        context.applicationContext,
        db,
        settingsStore
    )
) {
    constructor(context: Context, dao: Zap2Dao) : this(
        context.applicationContext,
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        dao
    )

    private val mediaDir: File by lazy {
        File(context.filesDir, "media").apply { mkdirs() }
    }

    private val downloadsDir: File by lazy {
        File(context.filesDir, "downloads").apply { mkdirs() }
    }

    private val savedGifsDir: File by lazy {
        File(context.filesDir, "saved_gifs").apply { mkdirs() }
    }

    private val savedStickersDir: File by lazy {
        File(context.filesDir, "saved_stickers").apply { mkdirs() }
    }

    private val scheduledMediaDir: File by lazy {
        File(context.filesDir, "scheduled_media").apply { mkdirs() }
    }

    private val _uploadProgress = MutableStateFlow<UploadProgressState?>(null)
    val uploadProgress: StateFlow<UploadProgressState?> = _uploadProgress.asStateFlow()

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("Usuário deve estar autenticado para acessar conversas.")

    fun observeChats(uid: String): Flow<List<ChatRoom>> = flow {
        val path = "chats"
        emitAll(
            db.collection("chats")
                .whereArrayContains("participantUids", uid)
                .snapshots()
                .map { snapshot ->
                    val chats = snapshot.toObjects(
                        ChatRoom::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.lastMessageAt?.toDate()?.time ?: 0L }

                    dao.upsertChats(
                        chats.map { c ->
                            LocalChatEntity(
                                chatId = c.id,
                                chatType = c.chatType,
                                title = c.title,
                                description = c.description,
                                participantUidsJson = Json.encodeToString(c.participantUids),
                                adminUidsJson = Json.encodeToString(c.adminUids),
                                participantNamesJson = Json.encodeToString(c.participantNames),
                                lastMessageText = c.lastMessageText,
                                lastMessageType = c.lastMessageType,
                                lastMessageSenderId = c.lastMessageSenderId,
                                lastMessageAtMs = c.lastMessageAt?.toDate()?.time ?: System.currentTimeMillis()
                            )
                        }
                    )
                    chats
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

    fun observeChat(chatId: String, uid: String): Flow<ChatRoom?> = flow {
        val path = "chats/$chatId"
        emitAll(
            db.collection("chats").document(chatId)
                .snapshots()
                .map { it.toObject(ChatRoom::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
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

    fun observeMessages(chatId: String, uid: String): Flow<List<ChatMessage>> = flow {
        val path = "chats/$chatId/messages"
        emitAll(
            db.collection("chats").document(chatId).collection("messages")
                .whereArrayContains("participantUids", uid)
                .snapshots()
                .map { snapshot ->
                    val allMsgs = snapshot.toObjects(
                        ChatMessage::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                    val hiddenIds = allMsgs.filter { uid in it.deletedFor }.map { it.id }
                    hiddenIds.forEach { dao.deleteMessageById(it) }

                    val msgs = allMsgs
                        .filter { uid !in it.deletedFor }
                        .sortedBy { it.createdAt?.toDate()?.time ?: Long.MAX_VALUE }

                    dao.upsertMessages(
                        msgs.map { m ->
                            LocalMessageEntity(
                                messageId = m.id,
                                chatId = m.chatId,
                                senderId = m.senderId,
                                senderName = m.senderName,
                                messageType = m.messageType,
                                text = m.text,
                                mediaUrl = m.mediaUrl,
                                localMediaPath = m.localMediaPath,
                                mediaDurationMs = m.mediaDurationMs,
                                fileName = m.fileName,
                                fileSizeBytes = m.fileSizeBytes,
                                mimeType = m.mimeType,
                                edited = m.edited,
                                createdAtMs = m.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
                                audioUrl = m.audioUrl,
                                audioMimeType = m.audioMimeType,
                                localAudioPath = m.localAudioPath,
                                viewOnce = m.viewOnce,
                                viewedByJson = Json.encodeToString(m.viewedBy),
                                aiGenerated = m.aiGenerated,
                                aiPersona = m.aiPersona,
                                aiModel = m.aiModel
                            )
                        }
                    )
                    msgs
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

    fun observeTyping(chatId: String, uid: String): Flow<List<TypingStatus>> = flow {
        val path = "chats/$chatId/typing"
        emitAll(
            db.collection("chats").document(chatId).collection("typing")
                .whereArrayContains("participantUids", uid)
                .snapshots()
                .map { snapshot ->
                    val now = System.currentTimeMillis()
                    snapshot.toObjects(
                        TypingStatus::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).filter { status ->
                        status.typing &&
                            status.uid != uid &&
                            (status.updatedAt == null || (now - status.updatedAt.toDate().time) < 12_000L)
                    }
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

    fun observePeerProfile(peerUid: String, currentUid: String): Flow<PublicProfile?> = flow {
        val path = "public_profiles/$peerUid"
        emitAll(
            db.collection("public_profiles").document(peerUid)
                .snapshots()
                .map {
                    it.toObject(
                        PublicProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == currentUid) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    suspend fun setTypingStatus(
        chatId: String,
        displayName: String,
        participantUids: List<String>,
        typing: Boolean
    ) {
        val uid = auth.currentUser?.uid ?: return
        if (chatId.isBlank() || uid !in participantUids) return
        val path = "chats/$chatId/typing/$uid"
        try {
            db.collection("chats").document(chatId)
                .collection("typing").document(uid)
                .set(
                    mapOf(
                        "uid" to uid,
                        "displayName" to displayName.take(80).ifBlank { "Usuário" },
                        "participantUids" to participantUids.take(50),
                        "typing" to typing,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
        }
    }

    suspend fun getOrCreateDirectChat(
        currentUserName: String,
        peerUid: String,
        peerName: String
    ): Result<String> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val cleanPeerUid = peerUid.trim().take(128)
        if (cleanPeerUid.isBlank()) {
            return Result.failure(IllegalArgumentException("Contato inválido para iniciar conversa."))
        }
        val safePeerName = peerName.trim().take(100).ifBlank { "Contato WhatwillIdo" }
        val sorted = listOf(uid, cleanPeerUid).sorted()
        val chatId = "direct_${sorted[0]}_${sorted[1]}".take(128)
        val docPath = "chats/$chatId"

        return try {
            val chatRef = db.collection("chats").document(chatId)
            val snap = chatRef.get().await()
            if (!snap.exists()) {
                val participants = listOf(uid, cleanPeerUid).distinct()
                val namesMap = mapOf(
                    uid to currentUserName.trim().take(80).ifBlank { "Você" },
                    cleanPeerUid to safePeerName.take(80)
                )
                chatRef.set(
                    mapOf(
                        "chatType" to "DIRECT",
                        "title" to safePeerName,
                        "createdBy" to uid,
                        "participantUids" to participants,
                        "adminUids" to participants,
                        "participantNames" to namesMap,
                        "lastMessageText" to "Conversa iniciada",
                        "lastMessageType" to "SYSTEM",
                        "lastMessageSenderId" to uid,
                        "lastMessageAt" to FieldValue.serverTimestamp(),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(chatId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun createGroupChat(
        creatorName: String,
        title: String,
        description: String,
        memberProfiles: List<Pair<String, String>>
    ): Result<String> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val cleanTitle = title.trim().take(100)
        if (cleanTitle.isBlank()) {
            return Result.failure(IllegalArgumentException("Informe o nome do grupo."))
        }
        val chatId = "group_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        val docPath = "chats/$chatId"

        return try {
            val namesMap = linkedMapOf<String, String>()
            namesMap[uid] = creatorName.take(80).ifBlank { "Você" }
            memberProfiles.forEach { (mUid, mName) ->
                if (mUid.isNotBlank()) namesMap[mUid] = mName.take(80).ifBlank { "Participante" }
            }
            val participants = namesMap.keys.toList().take(50)

            val payload = mutableMapOf<String, Any?>(
                "chatType" to "GROUP",
                "title" to cleanTitle,
                "description" to description.trim().take(300).takeIf { it.isNotEmpty() },
                "createdBy" to uid,
                "participantUids" to participants,
                "adminUids" to listOf(uid),
                "participantNames" to namesMap,
                "lastMessageText" to "$creatorName criou o grupo \"$cleanTitle\"",
                "lastMessageType" to "SYSTEM",
                "lastMessageSenderId" to uid,
                "lastMessageAt" to FieldValue.serverTimestamp(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>

            db.collection("chats").document(chatId).set(payload).await()

            val sysMsgId = UUID.randomUUID().toString().replace("-", "")
            db.collection("chats").document(chatId).collection("messages").document(sysMsgId).set(
                mapOf(
                    "chatId" to chatId,
                    "senderId" to uid,
                    "senderName" to creatorName.take(80).ifBlank { "Você" },
                    "participantUids" to participants,
                    "messageType" to "SYSTEM",
                    "text" to "$creatorName criou o grupo \"$cleanTitle\"",
                    "deliveredTo" to listOf(uid),
                    "readBy" to listOf(uid),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            Result.success(chatId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateGroupInfo(
        chatId: String,
        title: String,
        description: String
    ): Result<Unit> {
        val docPath = "chats/$chatId"
        return try {
            requireUserId()
            val cleanTitle = title.trim().take(100)
            require(cleanTitle.isNotEmpty()) { "Nome do grupo inválido." }
            db.collection("chats").document(chatId).update(
                mapOf(
                    "title" to cleanTitle,
                    "description" to description.trim().take(300),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun addGroupParticipant(
        chat: ChatRoom,
        newUid: String,
        newName: String,
        actorName: String
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docPath = "chats/${chat.id}"
        return try {
            val updatedParticipants = (chat.participantUids + newUid).distinct().take(50)
            val updatedNames = chat.participantNames.toMutableMap().apply {
                this[newUid] = newName.take(80).ifBlank { "Participante" }
            }
            val sysText = "$actorName adicionou $newName"
            db.collection("chats").document(chat.id).update(
                mapOf(
                    "participantUids" to updatedParticipants,
                    "participantNames" to updatedNames,
                    "lastMessageText" to sysText,
                    "lastMessageType" to "SYSTEM",
                    "lastMessageSenderId" to uid,
                    "lastMessageAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            val msgId = UUID.randomUUID().toString().replace("-", "")
            db.collection("chats").document(chat.id).collection("messages").document(msgId).set(
                mapOf(
                    "chatId" to chat.id,
                    "senderId" to uid,
                    "senderName" to actorName.take(80),
                    "participantUids" to updatedParticipants,
                    "messageType" to "SYSTEM",
                    "text" to sysText,
                    "deliveredTo" to listOf(uid),
                    "readBy" to listOf(uid),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun removeGroupParticipant(
        chat: ChatRoom,
        targetUid: String,
        actorName: String
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docPath = "chats/${chat.id}"
        return try {
            val updatedParticipants = chat.participantUids.filter { it != targetUid }
            val updatedAdmins = chat.adminUids.filter { it != targetUid }
                .ifEmpty { listOfNotNull(updatedParticipants.firstOrNull()) }
            val targetName = chat.participantNames[targetUid] ?: "Participante"
            val sysText = "$actorName removeu $targetName"
            val updatedNames = chat.participantNames.toMutableMap().apply { remove(targetUid) }

            db.collection("chats").document(chat.id).update(
                mapOf(
                    "participantUids" to updatedParticipants,
                    "adminUids" to updatedAdmins,
                    "participantNames" to updatedNames,
                    "lastMessageText" to sysText,
                    "lastMessageType" to "SYSTEM",
                    "lastMessageSenderId" to uid,
                    "lastMessageAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun toggleGroupAdmin(chat: ChatRoom, targetUid: String): Result<Unit> {
        val docPath = "chats/${chat.id}"
        return try {
            requireUserId()
            val updatedAdmins = if (targetUid in chat.adminUids) {
                val filtered = chat.adminUids.filter { it != targetUid }
                if (filtered.isEmpty()) return Result.failure(IllegalStateException("O grupo precisa de pelo menos 1 administrador."))
                filtered
            } else {
                (chat.adminUids + targetUid).distinct()
            }
            db.collection("chats").document(chat.id).update(
                mapOf(
                    "adminUids" to updatedAdmins,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun leaveGroup(chat: ChatRoom): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docPath = "chats/${chat.id}"
        return try {
            val remaining = chat.participantUids.filter { it != uid }
            if (remaining.isEmpty()) {
                db.collection("chats").document(chat.id).delete().await()
                return Result.success(Unit)
            }
            val updatedAdmins = chat.adminUids.filter { it != uid }.ifEmpty { listOf(remaining.first()) }
            db.collection("chats").document(chat.id).update(
                mapOf(
                    "participantUids" to remaining,
                    "adminUids" to updatedAdmins,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun sendTextMessage(
        chat: ChatRoom,
        senderName: String,
        text: String,
        viewOnce: Boolean = false,
        aiGenerated: Boolean = false,
        aiPersona: String? = null,
        aiModel: String? = null
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val cleanText = text.trim().take(4000)
        if (cleanText.isEmpty()) return Result.success(Unit)
        val msgId = UUID.randomUUID().toString().replace("-", "")
        val msgPath = "chats/${chat.id}/messages/$msgId"

        return try {
            val msgPayload = mutableMapOf<String, Any>(
                "chatId" to chat.id,
                "senderId" to uid,
                "senderName" to senderName.take(80).ifBlank { "Você" },
                "participantUids" to chat.participantUids,
                "messageType" to "TEXT",
                "text" to cleanText,
                "deliveredTo" to listOf(uid),
                "readBy" to listOf(uid),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (viewOnce && !aiGenerated) {
                msgPayload["viewOnce"] = true
                msgPayload["viewedBy"] = emptyList<String>()
            }
            if (aiGenerated) {
                msgPayload["aiGenerated"] = true
                if (!aiPersona.isNullOrBlank()) {
                    msgPayload["aiPersona"] = aiPersona.trim().take(80)
                }
                if (!aiModel.isNullOrBlank()) {
                    msgPayload["aiModel"] = aiModel.trim().take(80)
                }
            }
            db.collection("chats").document(chat.id)
                .collection("messages").document(msgId)
                .set(msgPayload)
                .await()

            val preview = when {
                aiGenerated -> "🤖 ${aiPersona?.takeIf { it.isNotBlank() } ?: "IA"}: $cleanText".take(500)
                viewOnce -> "🔒 Mensagem única"
                else -> cleanText.take(500)
            }
            db.collection("chats").document(chat.id).update(
                mapOf(
                    "lastMessageText" to preview,
                    "lastMessageType" to "TEXT",
                    "lastMessageSenderId" to uid,
                    "lastMessageAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            if (!aiGenerated) {
                setTypingStatus(chat.id, senderName, chat.participantUids, false)
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, msgPath)
            Result.failure(e)
        }
    }

    suspend fun sendImageMessage(
        chat: ChatRoom,
        senderName: String,
        imageUri: Uri,
        caption: String = "",
        viewOnce: Boolean = false
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val msgId = UUID.randomUUID().toString().replace("-", "")
        return try {
            val resolvedMime = context.contentResolver.getType(imageUri)?.lowercase() ?: "image/jpeg"
            val originalName = resolveDisplayName(imageUri) ?: "foto_$msgId.jpg"
            val isGif = resolvedMime == "image/gif" || originalName.endsWith(".gif", ignoreCase = true)
            val (localFile, finalMime) = withContext(Dispatchers.IO) {
                if (isGif) {
                    val safeGifName = sanitizeFileName(originalName, "gif")
                    val dest = File(mediaDir, "gif_${chat.id}_${msgId}_$safeGifName")
                    copyUriToFile(imageUri, dest)
                    dest to "image/gif"
                } else {
                    val dest = File(mediaDir, "img_${chat.id}_$msgId.jpg")
                    val bytes = compressUriToJpegBytes(imageUri, maxDim = 1600, quality = 88)
                    dest.writeBytes(bytes)
                    dest to "image/jpeg"
                }
            }

            val messageType = if (isGif) "GIF" else "IMAGE"
            val fileSize = localFile.length()
            val mediaUrl = uploadFileFree(
                chat = chat,
                senderId = uid,
                file = localFile,
                displayName = originalName,
                mimeType = finalMime
            )

            val previewText = when {
                viewOnce && isGif -> "🔒 GIF único"
                viewOnce -> "🔒 Foto única"
                isGif -> caption.trim().ifBlank { "🎞️ GIF" }
                else -> caption.trim().ifBlank { "📷 Foto" }
            }.take(500)
            val payload = mutableMapOf<String, Any?>(
                "chatId" to chat.id,
                "senderId" to uid,
                "senderName" to senderName.take(80).ifBlank { "Você" },
                "participantUids" to chat.participantUids,
                "messageType" to messageType,
                "text" to caption.trim().take(4000),
                "mediaUrl" to mediaUrl.take(MAX_STORAGE_URL_LENGTH),
                "localMediaPath" to localFile.absolutePath.take(500),
                "fileName" to originalName.take(255),
                "fileSizeBytes" to fileSize,
                "mimeType" to finalMime.take(128),
                "deliveredTo" to listOf(uid),
                "readBy" to listOf(uid),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (viewOnce) {
                payload["viewOnce"] = true
                payload["viewedBy"] = emptyList<String>()
            }
            val cleanPayload = payload.filterValues { it != null } as Map<String, Any>

            db.collection("chats").document(chat.id)
                .collection("messages").document(msgId)
                .set(cleanPayload)
                .await()

            db.collection("chats").document(chat.id).update(
                mapOf(
                    "lastMessageText" to previewText,
                    "lastMessageType" to messageType,
                    "lastMessageSenderId" to uid,
                    "lastMessageAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "chats/${chat.id}/messages/$msgId")
            Result.failure(e)
        } finally {
            _uploadProgress.value = null
        }
    }

    suspend fun sendGifFileMessage(
        chat: ChatRoom,
        senderName: String,
        gifFile: File,
        caption: String = "",
        viewOnce: Boolean = false
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val msgId = UUID.randomUUID().toString().replace("-", "")
        return try {
            val savedLocal = withContext(Dispatchers.IO) {
                val dest = File(mediaDir, "gif_${chat.id}_$msgId.gif")
                gifFile.copyTo(dest, overwrite = true)
                dest
            }
            val fileSize = savedLocal.length()
            val mediaUrl = uploadFileFree(
                chat = chat,
                senderId = uid,
                file = savedLocal,
                displayName = gifFile.name.ifBlank { "animado.gif" },
                mimeType = "image/gif"
            )
            val previewText = when {
                viewOnce -> "🔒 GIF único"
                caption.isNotBlank() -> caption.trim()
                else -> "🎞️ GIF"
            }.take(500)
            val payload = mutableMapOf<String, Any>(
                "chatId" to chat.id,
                "senderId" to uid,
                "senderName" to senderName.take(80).ifBlank { "Você" },
                "participantUids" to chat.participantUids,
                "messageType" to "GIF",
                "text" to caption.trim().take(4000),
                "mediaUrl" to mediaUrl.take(MAX_STORAGE_URL_LENGTH),
                "localMediaPath" to savedLocal.absolutePath.take(500),
                "fileName" to savedLocal.name.take(255),
                "fileSizeBytes" to fileSize,
                "mimeType" to "image/gif",
                "deliveredTo" to listOf(uid),
                "readBy" to listOf(uid),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (viewOnce) {
                payload["viewOnce"] = true
                payload["viewedBy"] = emptyList<String>()
            }
            db.collection("chats").document(chat.id)
                .collection("messages").document(msgId)
                .set(payload)
                .await()

            db.collection("chats").document(chat.id).update(
                mapOf(
                    "lastMessageText" to previewText,
                    "lastMessageType" to "GIF",
                    "lastMessageSenderId" to uid,
                    "lastMessageAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "chats/${chat.id}/messages/$msgId")
            Result.failure(e)
        } finally {
            _uploadProgress.value = null
        }
    }

    suspend fun sendStickerMessage(
        chat: ChatRoom,
        senderName: String,
        stickerFile: File,
        viewOnce: Boolean = false
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val msgId = UUID.randomUUID().toString().replace("-", "")
        return try {
            val savedLocal = withContext(Dispatchers.IO) {
                val dest = File(mediaDir, "fig_${chat.id}_$msgId.png")
                stickerFile.copyTo(dest, overwrite = true)
                dest
            }
            val fileSize = savedLocal.length()
            val mediaUrl = uploadFileFree(
                chat = chat,
                senderId = uid,
                file = savedLocal,
                displayName = "fig_$msgId.png",
                mimeType = "image/png"
            )
            val previewText = if (viewOnce) "🔒 Fig única" else "✨ Fig"
            val payload = mutableMapOf<String, Any>(
                "chatId" to chat.id,
                "senderId" to uid,
                "senderName" to senderName.take(80).ifBlank { "Você" },
                "participantUids" to chat.participantUids,
                "messageType" to "STICKER",
                "text" to "",
                "mediaUrl" to mediaUrl.take(MAX_STORAGE_URL_LENGTH),
                "localMediaPath" to savedLocal.absolutePath.take(500),
                "fileName" to savedLocal.name.take(255),
                "fileSizeBytes" to fileSize,
                "mimeType" to "image/png",
                "deliveredTo" to listOf(uid),
                "readBy" to listOf(uid),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (viewOnce) {
                payload["viewOnce"] = true
                payload["viewedBy"] = emptyList<String>()
            }
            db.collection("chats").document(chat.id)
                .collection("messages").document(msgId)
                .set(payload)
                .await()

            db.collection("chats").document(chat.id).update(
                mapOf(
                    "lastMessageText" to previewText,
                    "lastMessageType" to "STICKER",
                    "lastMessageSenderId" to uid,
                    "lastMessageAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "chats/${chat.id}/messages/$msgId")
            Result.failure(e)
        } finally {
            _uploadProgress.value = null
        }
    }

    suspend fun sendGifWithAudioMessage(
        chat: ChatRoom,
        senderName: String,
        gifFile: File,
        audioFile: File,
        audioDurationMs: Long,
        caption: String = "",
        viewOnce: Boolean = false
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val msgId = UUID.randomUUID().toString().replace("-", "")
        return try {
            val (savedGif, savedAudio) = withContext(Dispatchers.IO) {
                val g = File(mediaDir, "gifaud_${chat.id}_$msgId.gif")
                gifFile.copyTo(g, overwrite = true)
                val a = File(mediaDir, "gifaud_${chat.id}_$msgId.m4a")
                audioFile.copyTo(a, overwrite = true)
                g to a
            }
            val gifUrl = uploadFileFree(
                chat = chat,
                senderId = uid,
                file = savedGif,
                displayName = "gif_${msgId}.gif",
                mimeType = "image/gif"
            )
            val audioUrl = uploadFileFree(
                chat = chat,
                senderId = uid,
                file = savedAudio,
                displayName = "audio_${msgId}.m4a",
                mimeType = "audio/mp4"
            )
            val previewText = when {
                viewOnce -> "🔒 GIF c/ áudio único"
                caption.isNotBlank() -> caption.trim()
                else -> "🎞️🔊 GIF com áudio"
            }.take(500)

            val payload = mutableMapOf<String, Any>(
                "chatId" to chat.id,
                "senderId" to uid,
                "senderName" to senderName.take(80).ifBlank { "Você" },
                "participantUids" to chat.participantUids,
                "messageType" to "GIF_AUDIO",
                "text" to caption.trim().take(4000),
                "mediaUrl" to gifUrl.take(MAX_STORAGE_URL_LENGTH),
                "localMediaPath" to savedGif.absolutePath.take(500),
                "fileName" to savedGif.name.take(255),
                "fileSizeBytes" to savedGif.length(),
                "mimeType" to "image/gif",
                "audioUrl" to audioUrl.take(MAX_STORAGE_URL_LENGTH),
                "audioFileName" to savedAudio.name.take(255),
                "audioMimeType" to "audio/mp4",
                "localAudioPath" to savedAudio.absolutePath.take(500),
                "mediaDurationMs" to audioDurationMs.coerceAtLeast(0L),
                "deliveredTo" to listOf(uid),
                "readBy" to listOf(uid),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (viewOnce) {
                payload["viewOnce"] = true
                payload["viewedBy"] = emptyList<String>()
            }
            db.collection("chats").document(chat.id)
                .collection("messages").document(msgId)
                .set(payload)
                .await()

            db.collection("chats").document(chat.id).update(
                mapOf(
                    "lastMessageText" to previewText,
                    "lastMessageType" to "GIF_AUDIO",
                    "lastMessageSenderId" to uid,
                    "lastMessageAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "chats/${chat.id}/messages/$msgId")
            Result.failure(e)
        } finally {
            _uploadProgress.value = null
        }
    }

    suspend fun sendAudioMessage(
        chat: ChatRoom,
        senderName: String,
        audioFile: File,
        durationMs: Long,
        viewOnce: Boolean = false
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val msgId = UUID.randomUUID().toString().replace("-", "")
        return try {
            val savedLocal = withContext(Dispatchers.IO) {
                val dest = File(mediaDir, "aud_${chat.id}_$msgId.m4a")
                audioFile.copyTo(dest, overwrite = true)
                dest
            }

            val fileSize = savedLocal.length()
            val mediaUrl = uploadFileFree(
                chat = chat,
                senderId = uid,
                file = savedLocal,
                displayName = "Áudio de voz.m4a",
                mimeType = "audio/mp4"
            )

            val payload = mutableMapOf<String, Any>(
                "chatId" to chat.id,
                "senderId" to uid,
                "senderName" to senderName.take(80).ifBlank { "Você" },
                "participantUids" to chat.participantUids,
                "messageType" to "AUDIO",
                "text" to "",
                "mediaUrl" to mediaUrl.take(MAX_STORAGE_URL_LENGTH),
                "localMediaPath" to savedLocal.absolutePath.take(500),
                "mediaDurationMs" to durationMs.coerceAtLeast(0L),
                "fileName" to savedLocal.name.take(255),
                "fileSizeBytes" to fileSize,
                "mimeType" to "audio/mp4",
                "deliveredTo" to listOf(uid),
                "readBy" to listOf(uid),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (viewOnce) {
                payload["viewOnce"] = true
                payload["viewedBy"] = emptyList<String>()
            }

            db.collection("chats").document(chat.id)
                .collection("messages").document(msgId)
                .set(payload)
                .await()

            val preview = if (viewOnce) "🔒 Áudio único" else "🎤 Mensagem de voz"
            db.collection("chats").document(chat.id).update(
                mapOf(
                    "lastMessageText" to preview,
                    "lastMessageType" to "AUDIO",
                    "lastMessageSenderId" to uid,
                    "lastMessageAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "chats/${chat.id}/messages/$msgId")
            Result.failure(e)
        } finally {
            _uploadProgress.value = null
        }
    }

    suspend fun sendVideoMessage(
        chat: ChatRoom,
        senderName: String,
        videoUri: Uri,
        caption: String = "",
        viewOnce: Boolean = false
    ): Result<Unit> {
        val originalName = resolveDisplayName(videoUri) ?: "video_${System.currentTimeMillis()}.mp4"
        val mimeType = context.contentResolver.getType(videoUri)?.lowercase() ?: "video/mp4"
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?: originalName.substringAfterLast('.', "mp4").lowercase().ifBlank { "mp4" }
        val tempFile = withContext(Dispatchers.IO) {
            val dest = File(context.cacheDir, "tmp_vid_${UUID.randomUUID()}.$ext")
            copyUriToFile(videoUri, dest)
            dest
        }
        return try {
            sendVideoFileMessage(chat, senderName, tempFile, originalName, mimeType, caption, viewOnce)
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    suspend fun sendVideoFileMessage(
        chat: ChatRoom,
        senderName: String,
        videoFile: File,
        originalName: String = videoFile.name.ifBlank { "video.mp4" },
        mimeType: String = "video/mp4",
        caption: String = "",
        viewOnce: Boolean = false
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val msgId = UUID.randomUUID().toString().replace("-", "")
        return try {
            val ext = originalName.substringAfterLast('.', "mp4").lowercase().ifBlank { "mp4" }
            val (localFile, durationMs) = withContext(Dispatchers.IO) {
                val dest = File(mediaDir, "vid_${chat.id}_$msgId.$ext")
                videoFile.copyTo(dest, overwrite = true)
                val dur = readVideoDurationMs(dest)
                dest to dur
            }

            val fileSize = localFile.length()
            val mediaUrl = uploadFileFree(
                chat = chat,
                senderId = uid,
                file = localFile,
                displayName = originalName,
                mimeType = mimeType
            )

            val previewText = when {
                viewOnce -> "🔒 Vídeo único"
                else -> caption.trim().ifBlank { "🎬 Vídeo ($originalName)" }
            }.take(500)
            val payload = mutableMapOf<String, Any?>(
                "chatId" to chat.id,
                "senderId" to uid,
                "senderName" to senderName.take(80).ifBlank { "Você" },
                "participantUids" to chat.participantUids,
                "messageType" to "VIDEO",
                "text" to caption.trim().take(4000),
                "mediaUrl" to mediaUrl.take(MAX_STORAGE_URL_LENGTH),
                "localMediaPath" to localFile.absolutePath.take(500),
                "mediaDurationMs" to durationMs.coerceAtLeast(0L),
                "fileName" to originalName.take(255),
                "fileSizeBytes" to fileSize,
                "mimeType" to mimeType.take(128),
                "deliveredTo" to listOf(uid),
                "readBy" to listOf(uid),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (viewOnce) {
                payload["viewOnce"] = true
                payload["viewedBy"] = emptyList<String>()
            }
            val cleanPayload = payload.filterValues { it != null } as Map<String, Any>

            db.collection("chats").document(chat.id)
                .collection("messages").document(msgId)
                .set(cleanPayload)
                .await()

            db.collection("chats").document(chat.id).update(
                mapOf(
                    "lastMessageText" to previewText,
                    "lastMessageType" to "VIDEO",
                    "lastMessageSenderId" to uid,
                    "lastMessageAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "chats/${chat.id}/messages/$msgId")
            Result.failure(e)
        } finally {
            _uploadProgress.value = null
        }
    }

    suspend fun sendDocumentMessage(
        chat: ChatRoom,
        senderName: String,
        fileUri: Uri,
        caption: String = ""
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val msgId = UUID.randomUUID().toString().replace("-", "")
        return try {
            val originalName = resolveDisplayName(fileUri) ?: "arquivo_$msgId"
            val detectedMime = resolveMimeType(fileUri, originalName)
            val ext = originalName.substringAfterLast('.', "").lowercase().ifBlank {
                MimeTypeMap.getSingleton().getExtensionFromMimeType(detectedMime) ?: "bin"
            }

            val messageType = classifyDocumentType(originalName, detectedMime)
            if (messageType == "IMAGE") {
                return sendImageMessage(chat, senderName, fileUri, caption)
            }
            if (messageType == "VIDEO") {
                return sendVideoMessage(chat, senderName, fileUri, caption)
            }

            val localFile = withContext(Dispatchers.IO) {
                val safeName = sanitizeFileName(originalName, ext)
                val dest = File(downloadsDir, "${chat.id}_${msgId}_$safeName")
                copyUriToFile(fileUri, dest)
                dest
            }

            val fileSize = localFile.length()
            if (fileSize <= 0L) {
                localFile.delete()
                return Result.failure(IllegalArgumentException("O arquivo selecionado está vazio."))
            }

            val mediaUrl = uploadFileFree(
                chat = chat,
                senderId = uid,
                file = localFile,
                displayName = originalName,
                mimeType = detectedMime
            )

            val previewLabel = when (messageType) {
                "PDF" -> "📄 PDF: $originalName"
                "APK" -> "📦 APK: $originalName"
                "ZIP" -> "🗜️ ZIP: $originalName"
                else -> "📎 Arquivo: $originalName"
            }
            val previewText = caption.trim().ifBlank { previewLabel }.take(500)

            val payload = mutableMapOf<String, Any?>(
                "chatId" to chat.id,
                "senderId" to uid,
                "senderName" to senderName.take(80).ifBlank { "Você" },
                "participantUids" to chat.participantUids,
                "messageType" to messageType,
                "text" to caption.trim().take(4000),
                "mediaUrl" to mediaUrl.take(MAX_STORAGE_URL_LENGTH),
                "localMediaPath" to localFile.absolutePath.take(500),
                "fileName" to originalName.take(255),
                "fileSizeBytes" to fileSize,
                "mimeType" to detectedMime.take(128),
                "deliveredTo" to listOf(uid),
                "readBy" to listOf(uid),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null } as Map<String, Any>

            db.collection("chats").document(chat.id)
                .collection("messages").document(msgId)
                .set(payload)
                .await()

            db.collection("chats").document(chat.id).update(
                mapOf(
                    "lastMessageText" to previewText,
                    "lastMessageType" to messageType,
                    "lastMessageSenderId" to uid,
                    "lastMessageAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "chats/${chat.id}/messages/$msgId")
            Result.failure(e)
        } finally {
            _uploadProgress.value = null
        }
    }

    suspend fun downloadMessageFile(
        message: ChatMessage,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val localExisting = message.localMediaPath?.let(::File)?.takeIf { it.exists() && it.length() > 0L }
            if (localExisting != null) {
                onProgress(1f)
                return@withContext Result.success(localExisting)
            }

            val preferredName = sanitizeFileName(
                message.fileName ?: "arquivo_${message.id}",
                defaultExtForType(message.messageType)
            )
            val cachedDest = File(downloadsDir, "${message.id}_$preferredName")
            if (cachedDest.exists() && cachedDest.length() > 0L &&
                (message.fileSizeBytes == null || message.fileSizeBytes <= 0L || cachedDest.length() == message.fileSizeBytes)
            ) {
                onProgress(1f)
                return@withContext Result.success(cachedDest)
            }

            val url = message.mediaUrl?.takeIf { it.isNotBlank() }
                ?: return@withContext Result.failure(IllegalStateException("URL do arquivo indisponível."))

            val resolved = freeStorageManager.resolveToFile(
                url = url,
                preferredFileName = preferredName,
                expectedSizeBytes = message.fileSizeBytes,
                onProgress = onProgress
            ).getOrElse { return@withContext Result.failure(it) }

            if (resolved.absolutePath != cachedDest.absolutePath) {
                resolved.copyTo(cachedDest, overwrite = true)
            }
            onProgress(1f)
            Result.success(cachedDest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun uploadFileFree(
        chat: ChatRoom,
        senderId: String,
        file: File,
        displayName: String,
        mimeType: String
    ): String {
        val totalSize = file.length().coerceAtLeast(1L)
        _uploadProgress.value = UploadProgressState(
            fileName = displayName,
            bytesTransferred = 0L,
            totalBytes = totalSize,
            progress = 0f
        )

        return freeStorageManager.uploadChatFile(
            chatId = chat.id,
            senderId = senderId,
            participantUids = chat.participantUids,
            file = file,
            displayName = displayName,
            mimeType = mimeType
        ) { transferred, total ->
            _uploadProgress.value = UploadProgressState(
                fileName = displayName,
                bytesTransferred = transferred,
                totalBytes = total.coerceAtLeast(1L),
                progress = (transferred.toFloat() / total.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
            )
        }
    }

    suspend fun editMessage(
        chat: ChatRoom,
        message: ChatMessage,
        newText: String,
        isLatestMessage: Boolean
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        if (message.senderId != uid) {
            return Result.failure(IllegalStateException("Você só pode editar suas próprias mensagens."))
        }
        val cleanText = newText.trim().take(4000)
        if (message.messageType == "TEXT" && cleanText.isEmpty()) {
            return Result.failure(IllegalArgumentException("A mensagem não pode ficar vazia."))
        }
        val msgPath = "chats/${chat.id}/messages/${message.id}"
        return try {
            db.collection("chats").document(chat.id)
                .collection("messages").document(message.id)
                .update(
                    mapOf(
                        "text" to cleanText,
                        "edited" to true,
                        "editedAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()

            if (isLatestMessage) {
                val preview = when (message.messageType) {
                    "IMAGE" -> cleanText.ifBlank { "📷 Foto" }
                    "VIDEO" -> cleanText.ifBlank { "🎬 Vídeo" }
                    "AUDIO" -> "🎤 Mensagem de voz"
                    "PDF" -> cleanText.ifBlank { "📄 PDF: ${message.fileName ?: "Documento"}" }
                    "APK" -> cleanText.ifBlank { "📦 APK: ${message.fileName ?: "Aplicativo"}" }
                    "ZIP" -> cleanText.ifBlank { "🗜️ ZIP: ${message.fileName ?: "Arquivo"}" }
                    "FILE" -> cleanText.ifBlank { "📎 Arquivo: ${message.fileName ?: "Anexo"}" }
                    else -> cleanText
                }.take(500)
                db.collection("chats").document(chat.id).update(
                    mapOf(
                        "lastMessageText" to preview,
                        "lastMessageType" to message.messageType,
                        "lastMessageSenderId" to uid,
                        "lastMessageAt" to (message.createdAt ?: FieldValue.serverTimestamp()),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, msgPath)
            Result.failure(e)
        }
    }

    suspend fun deleteMessageForMe(chatId: String, messageId: String): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val msgPath = "chats/$chatId/messages/$messageId"
        return try {
            db.collection("chats").document(chatId)
                .collection("messages").document(messageId)
                .update(
                    mapOf(
                        "deletedFor" to FieldValue.arrayUnion(uid),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            dao.deleteMessageById(messageId)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, msgPath)
            Result.failure(e)
        }
    }

    suspend fun deleteMessageForEveryone(
        chat: ChatRoom,
        message: ChatMessage,
        remainingMessagesAfterDelete: List<ChatMessage>
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        if (message.senderId != uid) {
            return Result.failure(IllegalStateException("Você só pode apagar para todos as suas próprias mensagens."))
        }
        val msgPath = "chats/${chat.id}/messages/${message.id}"
        return try {
            db.collection("chats").document(chat.id)
                .collection("messages").document(message.id)
                .delete()
                .await()

            dao.deleteMessageById(message.id)
            message.localMediaPath?.let { path ->
                runCatching { File(path).delete() }
            }
            freeStorageManager.deleteChatChunksIfNeeded(chat.id, message.mediaUrl)

            // Update chat preview cleanly to the previous message (or blank), leaving zero trace
            val fallback = remainingMessagesAfterDelete.lastOrNull()
            val previewText = when (fallback?.messageType) {
                null -> ""
                "IMAGE" -> fallback.text.ifBlank { "📷 Foto" }
                "VIDEO" -> fallback.text.ifBlank { "🎬 Vídeo" }
                "AUDIO" -> "🎤 Mensagem de voz"
                "PDF" -> fallback.text.ifBlank { "📄 PDF: ${fallback.fileName ?: "Documento"}" }
                "APK" -> fallback.text.ifBlank { "📦 APK: ${fallback.fileName ?: "Aplicativo"}" }
                "ZIP" -> fallback.text.ifBlank { "🗜️ ZIP: ${fallback.fileName ?: "Arquivo"}" }
                "FILE" -> fallback.text.ifBlank { "📎 Arquivo: ${fallback.fileName ?: "Anexo"}" }
                else -> fallback.text
            }.take(500)

            db.collection("chats").document(chat.id).update(
                mapOf(
                    "lastMessageText" to previewText,
                    "lastMessageType" to (fallback?.messageType ?: "TEXT"),
                    "lastMessageSenderId" to (fallback?.senderId ?: uid),
                    "lastMessageAt" to (fallback?.createdAt ?: chat.createdAt ?: FieldValue.serverTimestamp()),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, msgPath)
            Result.failure(e)
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)?.trim()
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    private fun resolveMimeType(uri: Uri, fileName: String): String {
        val crMime = context.contentResolver.getType(uri)?.lowercase()
        if (!crMime.isNullOrBlank() && crMime != "application/octet-stream") {
            return crMime
        }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> "application/pdf"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            else -> crMime ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    private fun classifyDocumentType(fileName: String, mimeType: String): String {
        val lowerName = fileName.lowercase()
        val lowerMime = mimeType.lowercase()
        return when {
            lowerMime == "application/pdf" || lowerName.endsWith(".pdf") -> "PDF"
            lowerMime == "application/vnd.android.package-archive" || lowerName.endsWith(".apk") -> "APK"
            lowerMime == "application/zip" || lowerMime == "application/x-zip-compressed" || lowerName.endsWith(".zip") -> "ZIP"
            lowerMime.startsWith("image/") -> "IMAGE"
            lowerMime.startsWith("video/") -> "VIDEO"
            else -> "FILE"
        }
    }

    private fun sanitizeFileName(raw: String, fallbackExt: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9._\\- ()]"), "_").trim()
        return if (cleaned.isBlank()) "arquivo.$fallbackExt" else cleaned.take(120)
    }

    private fun defaultExtForType(messageType: String): String = when (messageType) {
        "PDF" -> "pdf"
        "APK" -> "apk"
        "ZIP" -> "zip"
        "IMAGE" -> "jpg"
        "VIDEO" -> "mp4"
        "AUDIO" -> "m4a"
        else -> "bin"
    }

    private fun copyUriToFile(uri: Uri, dest: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                }
            }
        } ?: throw IllegalStateException("Não foi possível ler o arquivo selecionado.")
    }

    private fun readVideoDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun compressUriToJpegBytes(
        imageUri: Uri,
        maxDim: Int = 1600,
        quality: Int = 88
    ): ByteArray {
        val rawTemp = File(context.cacheDir, "tmp_img_${UUID.randomUUID()}.bin")
        try {
            copyUriToFile(imageUri, rawTemp)

            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(rawTemp.absolutePath, boundsOpts)

            var sampleSize = 1
            while (boundsOpts.outWidth / sampleSize > maxDim * 2 || boundsOpts.outHeight / sampleSize > maxDim * 2) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = BitmapFactory.decodeFile(rawTemp.absolutePath, decodeOpts)
                ?: throw IllegalStateException("Arquivo selecionado não é uma imagem válida.")

            val scaled = if (decoded.width > maxDim || decoded.height > maxDim) {
                val ratio = minOf(maxDim.toFloat() / decoded.width, maxDim.toFloat() / decoded.height)
                val targetW = (decoded.width * ratio).toInt().coerceAtLeast(1)
                val targetH = (decoded.height * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else {
                decoded
            }

            return try {
                ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    out.toByteArray()
                }
            } finally {
                scaled.recycle()
            }
        } finally {
            rawTemp.delete()
        }
    }

    suspend fun markMessagesRead(chatId: String, uid: String, messages: List<ChatMessage>) {
        val unread = messages.filter { it.senderId != uid && uid !in it.readBy }
        if (unread.isEmpty()) return
        for (msg in unread.takeLast(20)) {
            try {
                val updatedDelivered = (msg.deliveredTo + uid).distinct()
                val updatedRead = (msg.readBy + uid).distinct()
                db.collection("chats").document(chatId)
                    .collection("messages").document(msg.id)
                    .update(
                        mapOf(
                            "deliveredTo" to updatedDelivered,
                            "readBy" to updatedRead,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    .await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.UPDATE, "chats/$chatId/messages/${msg.id}")
            }
        }
    }

    suspend fun markMessageViewOnceOpened(chatId: String, message: ChatMessage): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return try {
            db.collection("chats").document(chatId)
                .collection("messages").document(message.id)
                .update(
                    mapOf(
                        "viewedBy" to FieldValue.arrayUnion(uid),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            if (message.senderId != uid) {
                message.localMediaPath?.let { path -> runCatching { File(path).delete() } }
                message.localAudioPath?.let { path -> runCatching { File(path).delete() } }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "chats/$chatId/messages/${message.id}")
            Result.failure(e)
        }
    }

    suspend fun downloadMessageAudioFile(message: ChatMessage): Result<File> = withContext(Dispatchers.IO) {
        try {
            val localExisting = message.localAudioPath?.let(::File)?.takeIf { it.exists() && it.length() > 0L }
            if (localExisting != null) {
                return@withContext Result.success(localExisting)
            }
            val preferredName = sanitizeFileName(message.audioFileName ?: "audio_${message.id}.m4a", "m4a")
            val cachedDest = File(downloadsDir, "${message.id}_$preferredName")
            if (cachedDest.exists() && cachedDest.length() > 0L) {
                return@withContext Result.success(cachedDest)
            }
            val url = message.audioUrl?.takeIf { it.isNotBlank() }
                ?: return@withContext Result.failure(IllegalStateException("Áudio não disponível."))
            val resolved = freeStorageManager.resolveToFile(
                url = url,
                preferredFileName = preferredName,
                expectedSizeBytes = null,
                onProgress = {}
            ).getOrElse { return@withContext Result.failure(it) }
            if (resolved.absolutePath != cachedDest.absolutePath) {
                resolved.copyTo(cachedDest, overwrite = true)
            }
            Result.success(cachedDest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun observeSavedStickersAndGifs(): Flow<List<SavedStickerGifEntity>> =
        dao.observeSavedStickersAndGifs()

    suspend fun deleteSavedStickerOrGif(item: SavedStickerGifEntity) {
        withContext(Dispatchers.IO) {
            runCatching { File(item.localFilePath).delete() }
            item.localAudioPath?.let { path -> runCatching { File(path).delete() } }
            dao.deleteSavedStickerGifById(item.id)
        }
    }

    suspend fun saveCustomStickerToCollection(
        stickerFile: File,
        title: String = "Minha Fig",
        exportToGallery: Boolean = false
    ): Result<SavedStickerGifEntity> = withContext(Dispatchers.IO) {
        try {
            val id = UUID.randomUUID().toString().replace("-", "").take(16)
            val dest = File(savedStickersDir, "fig_${id}.png")
            stickerFile.copyTo(dest, overwrite = true)
            val entity = SavedStickerGifEntity(
                id = id,
                kind = "STICKER",
                title = title.trim().ifBlank { "Minha Fig" }.take(60),
                localFilePath = dest.absolutePath,
                createdAtMs = System.currentTimeMillis()
            )
            dao.upsertSavedStickerGif(entity)
            if (exportToGallery) {
                exportMediaToPicturesWhatwillIdo(dest, "image/png", "WhatwillIdo_Fig_$id.png")
            }
            Result.success(entity)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveCustomGifWithAudioToCollection(
        gifFile: File,
        audioFile: File,
        audioDurationMs: Long,
        title: String = "GIF com Áudio"
    ): Result<SavedStickerGifEntity> = withContext(Dispatchers.IO) {
        try {
            val id = UUID.randomUUID().toString().replace("-", "").take(16)
            val destGif = File(savedGifsDir, "gif_${id}.gif")
            gifFile.copyTo(destGif, overwrite = true)
            val destAud = File(savedGifsDir, "aud_${id}.m4a")
            audioFile.copyTo(destAud, overwrite = true)
            val entity = SavedStickerGifEntity(
                id = id,
                kind = "GIF_AUDIO",
                title = title.trim().ifBlank { "GIF com Áudio" }.take(60),
                localFilePath = destGif.absolutePath,
                localAudioPath = destAud.absolutePath,
                audioDurationMs = audioDurationMs.coerceAtLeast(0L),
                createdAtMs = System.currentTimeMillis()
            )
            dao.upsertSavedStickerGif(entity)
            Result.success(entity)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveMessageMediaToCollectionAndGallery(
        message: ChatMessage,
        exportToGallery: Boolean = true
    ): Result<SavedStickerGifEntity> = withContext(Dispatchers.IO) {
        try {
            if (message.viewOnce) {
                return@withContext Result.failure(IllegalStateException("Mídias de visualização única não podem ser salvas."))
            }
            val mainFile = downloadMessageFile(message).getOrElse { return@withContext Result.failure(it) }
            val kind = when (message.messageType) {
                "STICKER" -> "STICKER"
                "GIF_AUDIO" -> "GIF_AUDIO"
                else -> "GIF"
            }
            val ext = if (kind == "STICKER") "png" else "gif"
            val mime = if (kind == "STICKER") "image/png" else "image/gif"
            val targetDir = if (kind == "STICKER") savedStickersDir else savedGifsDir
            val id = "msg_${message.id.take(16)}"
            val destFile = File(targetDir, "${kind.lowercase()}_$id.$ext")
            mainFile.copyTo(destFile, overwrite = true)

            var destAudioPath: String? = null
            if (kind == "GIF_AUDIO" && !message.audioUrl.isNullOrBlank()) {
                val audFile = downloadMessageAudioFile(message).getOrNull()
                if (audFile != null && audFile.exists()) {
                    val audDest = File(savedGifsDir, "aud_$id.m4a")
                    audFile.copyTo(audDest, overwrite = true)
                    destAudioPath = audDest.absolutePath
                }
            }

            val title = when (kind) {
                "STICKER" -> "Fig salva"
                "GIF_AUDIO" -> "GIF c/ Áudio salvo"
                else -> message.fileName?.substringBeforeLast('.')?.ifBlank { "GIF salvo" } ?: "GIF salvo"
            }
            val entity = SavedStickerGifEntity(
                id = id,
                kind = kind,
                title = title.take(60),
                localFilePath = destFile.absolutePath,
                localAudioPath = destAudioPath,
                audioDurationMs = message.mediaDurationMs ?: 0L,
                remoteMediaUrl = message.mediaUrl,
                remoteAudioUrl = message.audioUrl,
                createdAtMs = System.currentTimeMillis()
            )
            dao.upsertSavedStickerGif(entity)

            if (exportToGallery) {
                exportMediaToPicturesWhatwillIdo(destFile, mime, "WhatwillIdo_${id}.$ext")
            }
            Result.success(entity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun exportMediaToPicturesWhatwillIdo(file: File, mimeType: String, displayName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/WhatwillIdo")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val itemUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(itemUri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
        }.onFailure { err ->
            Log.w(TAG, "Export to gallery failed: ${err.message}")
        }
    }

    fun copyUriToScheduledMedia(uri: Uri, extFallback: String): File {
        val ext = resolveDisplayName(uri)?.substringAfterLast('.', extFallback)?.lowercase()?.ifBlank { extFallback }
            ?: extFallback
        val dest = File(scheduledMediaDir, "sched_${UUID.randomUUID()}.$ext")
        copyUriToFile(uri, dest)
        return dest
    }

    fun copyFileToScheduledMedia(source: File, extFallback: String): File {
        val ext = source.extension.lowercase().ifBlank { extFallback }
        val dest = File(scheduledMediaDir, "sched_${UUID.randomUUID()}.$ext")
        source.copyTo(dest, overwrite = true)
        return dest
    }

    fun observeScheduledMessages(chatId: String): Flow<List<ScheduledMessageEntity>> =
        dao.observePendingScheduledForChat(chatId)

    suspend fun getChatRoomOnce(chatId: String): ChatRoom? {
        return try {
            db.collection("chats").document(chatId)
                .get()
                .await()
                .toObject(ChatRoom::class.java)
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val TAG = "ChatRepository"
        const val MAX_STORAGE_URL_LENGTH = 2048
    }
}
