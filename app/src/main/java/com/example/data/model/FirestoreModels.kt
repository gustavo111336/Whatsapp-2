package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val uid: String = "",
    val displayName: String = "",
    val about: String = "Disponível no Zap2",
    val phoneNumber: String? = null,
    val phoneVerified: Boolean = false,
    val email: String? = null,
    val photoUrl: String? = null,
    val fcmToken: String? = null,
    val online: Boolean = true,
    @ServerTimestamp val lastSeen: Timestamp? = null,
    val lastBackupUrl: String? = null,
    val lastBackupAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PublicProfile(
    @DocumentId val id: String = "",
    val uid: String = "",
    val displayName: String = "",
    val about: String = "Disponível no Zap2",
    val photoUrl: String? = null,
    val online: Boolean = false,
    @ServerTimestamp val lastSeen: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PhoneLookup(
    @DocumentId val id: String = "",
    val uid: String = "",
    val displayName: String = "",
    val photoUrl: String? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ContactEntry(
    @DocumentId val id: String = "",
    val ownerId: String = "",
    val peerUid: String = "",
    val displayName: String = "",
    val phoneNumber: String? = null,
    val photoUrl: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ChatRoom(
    @DocumentId val id: String = "",
    val chatType: String = "DIRECT",
    val title: String = "",
    val description: String? = null,
    val groupPhotoUrl: String? = null,
    val createdBy: String = "",
    val participantUids: List<String> = emptyList(),
    val adminUids: List<String> = emptyList(),
    val participantNames: Map<String, String> = emptyMap(),
    val lastMessageText: String = "",
    val lastMessageType: String = "TEXT",
    val lastMessageSenderId: String = "",
    @ServerTimestamp val lastMessageAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun resolveDisplayTitle(currentUserId: String): String {
        if (chatType == "GROUP") return title.ifBlank { "Grupo Zap2" }
        val peerUid = participantUids.firstOrNull { it != currentUserId }
        return if (peerUid != null) {
            participantNames[peerUid]?.takeIf { it.isNotBlank() } ?: title.ifBlank { "Contato Zap2" }
        } else {
            title.ifBlank { "Notas Pessoais" }
        }
    }

    fun peerUidForDirect(currentUserId: String): String? =
        if (chatType == "DIRECT") participantUids.firstOrNull { it != currentUserId } else null
}

@Keep
@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val chatId: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val participantUids: List<String> = emptyList(),
    val messageType: String = "TEXT",
    val text: String = "",
    val mediaUrl: String? = null,
    val localMediaPath: String? = null,
    val mediaDurationMs: Long? = null,
    val deliveredTo: List<String> = emptyList(),
    val readBy: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class TypingStatus(
    @DocumentId val id: String = "",
    val uid: String = "",
    val displayName: String = "",
    val participantUids: List<String> = emptyList(),
    val typing: Boolean = false,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CallSession(
    @DocumentId val id: String = "",
    val callerId: String = "",
    val callerName: String = "",
    val calleeId: String = "",
    val calleeName: String = "",
    val chatId: String = "",
    val participantUids: List<String> = emptyList(),
    val callType: String = "VIDEO",
    val status: String = "RINGING",
    val offerSdp: String? = null,
    val answerSdp: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class IceCandidateDoc(
    @DocumentId val id: String = "",
    val senderId: String = "",
    val participantUids: List<String> = emptyList(),
    val sdpMid: String = "",
    val sdpMLineIndex: Int = 0,
    val sdp: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)
