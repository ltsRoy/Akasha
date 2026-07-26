package com.MeshLink.android.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("SELECT * FROM messages WHERE isPrivate = 0 ORDER BY timestamp ASC")
    suspend fun getAllPublicMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE isPrivate = 1 ORDER BY timestamp ASC")
    suspend fun getAllPrivateMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :channel AND isPrivate = 0 ORDER BY timestamp ASC")
    suspend fun getChannelMessages(channel: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)
    
    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()
    
    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: String)
}
