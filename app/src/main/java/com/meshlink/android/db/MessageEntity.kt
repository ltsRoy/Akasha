package com.MeshLink.android.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId", "timestamp"])
    ]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,
    val conversationId: String, // peerID for private, channel name for channels
    val timestamp: Long,
    val isPrivate: Boolean,
    val messageJson: String
)
