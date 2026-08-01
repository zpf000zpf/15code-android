package com.fifteencode.android;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "messages",
        foreignKeys = @ForeignKey(entity = ConversationEntity.class,
                parentColumns = "id", childColumns = "conversationId", onDelete = ForeignKey.CASCADE),
        indices = @Index("conversationId"))
public class MessageEntity {
    @PrimaryKey(autoGenerate = true) public long id;
    public String conversationId;
    public String role;
    public String content;
    public long createdAt;

    public MessageEntity(String conversationId, String role, String content, long createdAt) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.createdAt = createdAt;
    }
}
