package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface Zap2Dao {
    @Query("SELECT * FROM local_chats ORDER BY lastMessageAtMs DESC")
    fun observeAllChats(): Flow<List<LocalChatEntity>>

    @Query("SELECT * FROM local_messages WHERE chatId = :chatId ORDER BY createdAtMs ASC")
    fun observeMessagesForChat(chatId: String): Flow<List<LocalMessageEntity>>

    @Query("SELECT * FROM local_contacts ORDER BY displayName ASC")
    fun observeAllContacts(): Flow<List<LocalContactEntity>>

    @Query("SELECT COUNT(*) FROM local_chats")
    suspend fun getChatCount(): Int

    @Query("SELECT COUNT(*) FROM local_messages")
    suspend fun getMessageCount(): Int

    @Query("SELECT COUNT(*) FROM local_contacts")
    suspend fun getContactCount(): Int

    @Query("SELECT * FROM local_chats")
    suspend fun getAllChatsOnce(): List<LocalChatEntity>

    @Query("SELECT * FROM local_messages")
    suspend fun getAllMessagesOnce(): List<LocalMessageEntity>

    @Query("SELECT * FROM local_contacts")
    suspend fun getAllContactsOnce(): List<LocalContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChats(chats: List<LocalChatEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<LocalMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: LocalMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContacts(contacts: List<LocalContactEntity>)

    @Query("DELETE FROM local_chats")
    suspend fun clearChats()

    @Query("DELETE FROM local_messages")
    suspend fun clearMessages()

    @Query("DELETE FROM local_contacts")
    suspend fun clearContacts()

    @RawQuery
    suspend fun checkpoint(query: SupportSQLiteQuery): Int
}
