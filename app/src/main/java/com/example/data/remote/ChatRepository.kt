package com.example.data.remote

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.R
import com.example.data.local.LocalChatEntity
import com.example.data.local.LocalMessageEntity
import com.example.data.local.Zap2Dao
import com.example.data.model.ChatMessage
import com.example.data.model.ChatRoom
import com.example.data.model.PublicProfile
import com.example.data.model.TypingStatus
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import com.google.firebase.storage.FirebaseStorage
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class ChatRepository(
    private val context: Context,
    private val db: FirebaseFirestore,
    private val dao: Zap2Dao,
    private val auth: FirebaseAuth = Firebase.auth,
    private val storage: FirebaseStorage = Firebase.storage
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
                    val msgs = snapshot.toObjects(
                        ChatMessage::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.createdAt?.toDate()?.time ?: Long.MAX_VALUE }

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
                                createdAtMs = m.createdAt?.toDate()?.time ?: System.currentTimeMillis()
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
        val safePeerName = peerName.trim().take(100).ifBlank { "Contato Zap2" }
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
        text: String
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
            val msgPayload = mapOf(
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
            db.collection("chats").document(chat.id)
                .collection("messages").document(msgId)
                .set(msgPayload)
                .await()

            db.collection("chats").document(chat.id).update(
                mapOf(
                    "lastMessageText" to cleanText.take(500),
                    "lastMessageType" to "TEXT",
                    "lastMessageSenderId" to uid,
                    "lastMessageAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            setTypingStatus(chat.id, senderName, chat.participantUids, false)
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
        caption: String = ""
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val msgId = UUID.randomUUID().toString().replace("-", "")
        return try {
            val localFile = withContext(Dispatchers.IO) {
                val dest = File(mediaDir, "img_${chat.id}_$msgId.jpg")
                context.contentResolver.openInputStream(imageUri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Não foi possível ler a imagem selecionada.")
                dest
            }

            val storageRef = storage.reference.child("chats/${chat.id}/media/${localFile.name}")
            val downloadUrl = try {
                storageRef.putFile(Uri.fromFile(localFile)).await()
                storageRef.downloadUrl.await().toString()
            } catch (storageEx: Exception) {
                Log.w(TAG, "Firebase Storage upload unavailable: ${storageEx.message}")
                return Result.failure(
                    IllegalStateException(
                        "Falha ao enviar mídia para o Firebase Storage: ${storageEx.localizedMessage ?: "verifique as regras de Storage no Console do Firebase."}"
                    )
                )
            }

            val previewText = caption.trim().ifBlank { "📷 Foto" }.take(500)
            val payload = mutableMapOf<String, Any?>(
                "chatId" to chat.id,
                "senderId" to uid,
                "senderName" to senderName.take(80).ifBlank { "Você" },
                "participantUids" to chat.participantUids,
                "messageType" to "IMAGE",
                "text" to caption.trim().take(4000),
                "mediaUrl" to downloadUrl.take(1000),
                "localMediaPath" to localFile.absolutePath.take(500),
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
                    "lastMessageType" to "IMAGE",
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
        }
    }

    suspend fun sendAudioMessage(
        chat: ChatRoom,
        senderName: String,
        audioFile: File,
        durationMs: Long
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

            val storageRef = storage.reference.child("chats/${chat.id}/media/${savedLocal.name}")
            val downloadUrl = try {
                storageRef.putFile(Uri.fromFile(savedLocal)).await()
                storageRef.downloadUrl.await().toString()
            } catch (storageEx: Exception) {
                Log.w(TAG, "Firebase Storage audio upload unavailable: ${storageEx.message}")
                return Result.failure(
                    IllegalStateException(
                        "Falha ao enviar áudio para o Firebase Storage: ${storageEx.localizedMessage ?: "verifique as regras de Storage no Console."}"
                    )
                )
            }

            val payload = mapOf(
                "chatId" to chat.id,
                "senderId" to uid,
                "senderName" to senderName.take(80).ifBlank { "Você" },
                "participantUids" to chat.participantUids,
                "messageType" to "AUDIO",
                "text" to "",
                "mediaUrl" to downloadUrl.take(1000),
                "localMediaPath" to savedLocal.absolutePath.take(500),
                "mediaDurationMs" to durationMs.coerceAtLeast(0L),
                "deliveredTo" to listOf(uid),
                "readBy" to listOf(uid),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )

            db.collection("chats").document(chat.id)
                .collection("messages").document(msgId)
                .set(payload)
                .await()

            db.collection("chats").document(chat.id).update(
                mapOf(
                    "lastMessageText" to "🎤 Mensagem de voz",
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

    private companion object {
        const val TAG = "ChatRepository"
    }
}
