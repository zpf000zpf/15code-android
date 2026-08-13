package com.fifteencode.android;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "image_versions",
        foreignKeys = @ForeignKey(entity = ConversationEntity.class,
                parentColumns = "id", childColumns = "conversationId", onDelete = ForeignKey.CASCADE),
        indices = {@Index("conversationId"), @Index("parentVersionId")})
public class ImageVersionEntity {
    @PrimaryKey @NonNull public String id;
    public String conversationId;
    public String parentVersionId;
    public String operation;
    public String status;
    public String prompt;
    public String localPath;
    public String thumbnailPath;
    public String mimeType;
    public String size;
    public String quality;
    public String format;
    public String clientRequestId;
    public long createdAt;
    @ColumnInfo(defaultValue = "0") public long completedAt;

    public ImageVersionEntity(@NonNull String id, String conversationId, String parentVersionId,
                              String operation, String status, String prompt, String localPath,
                              String thumbnailPath, String mimeType, String size, String quality,
                              String format, String clientRequestId, long createdAt, long completedAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.parentVersionId = parentVersionId;
        this.operation = operation;
        this.status = status;
        this.prompt = prompt;
        this.localPath = localPath;
        this.thumbnailPath = thumbnailPath;
        this.mimeType = mimeType;
        this.size = size;
        this.quality = quality;
        this.format = format;
        this.clientRequestId = clientRequestId;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
    }
}
