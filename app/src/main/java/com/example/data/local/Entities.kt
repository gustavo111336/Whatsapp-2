package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_chats")
data class LocalChatEntity(
    @PrimaryKey val chatId: String,
    val chatType: String,
    val title: String,
    val description: String?,
    val participantUidsJson: String,
    val adminUidsJson: String,
    val participantNamesJson: String,
    val lastMessageText: String,
    val lastMessageType: String,
    val lastMessageSenderId: String,
    val lastMessageAtMs: Long
)

@Entity(tableName = "local_messages")
data class LocalMessageEntity(
    @PrimaryKey val messageId: String,
    val chatId: String,
    val senderId: String,
    val senderName: String,
    val messageType: String,
    val text: String,
    val mediaUrl: String?,
    val localMediaPath: String?,
    val mediaDurationMs: Long?,
    val fileName: String? = null,
    val fileSizeBytes: Long? = null,
    val mimeType: String? = null,
    val edited: Boolean = false,
    val createdAtMs: Long,
    val audioUrl: String? = null,
    val audioMimeType: String? = null,
    val localAudioPath: String? = null,
    val viewOnce: Boolean = false,
    val viewedByJson: String = "[]"
)

@Entity(tableName = "local_contacts")
data class LocalContactEntity(
    @PrimaryKey val contactId: String,
    val ownerId: String,
    val peerUid: String,
    val username: String? = null,
    val displayName: String,
    val phoneNumber: String?,
    val photoUrl: String?,
    val updatedAtMs: Long
)

@Entity(tableName = "saved_stickers_gifs")
data class SavedStickerGifEntity(
    @PrimaryKey val id: String,
    val kind: String, // "GIF", "STICKER", "GIF_AUDIO"
    val title: String,
    val localFilePath: String,
    val localAudioPath: String? = null,
    val audioDurationMs: Long = 0L,
    val remoteMediaUrl: String? = null,
    val remoteAudioUrl: String? = null,
    val createdAtMs: Long
)

@Entity(tableName = "scheduled_messages")
data class ScheduledMessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val senderUid: String,
    val senderName: String,
    val chatTitle: String,
    val messageType: String, // "TEXT", "IMAGE", "GIF", "STICKER", "GIF_AUDIO", "VIDEO", "AUDIO"
    val text: String,
    val localMediaPath: String? = null,
    val localAudioPath: String? = null,
    val mediaDurationMs: Long = 0L,
    val mimeType: String? = null,
    val viewOnce: Boolean = false,
    val scheduledAtMs: Long,
    val status: String, // "PENDING", "SENDING", "SENT", "FAILED"
    val errorMessage: String? = null,
    val createdAtMs: Long
)
