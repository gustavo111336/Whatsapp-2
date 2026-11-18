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
    val createdAtMs: Long
)

@Entity(tableName = "local_contacts")
data class LocalContactEntity(
    @PrimaryKey val contactId: String,
    val ownerId: String,
    val peerUid: String,
    val displayName: String,
    val phoneNumber: String?,
    val photoUrl: String?,
    val updatedAtMs: Long
)
