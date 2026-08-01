package com.fifteencode.android;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "conversations")
public class ConversationEntity {
    @PrimaryKey @NonNull public String id;
    @NonNull public String title;
    public boolean pinned;
    public boolean deleted;
    public long createdAt;
    public long updatedAt;
    @NonNull public String draft;

    public ConversationEntity(@NonNull String id, @NonNull String title, boolean pinned,
                              boolean deleted, long createdAt, long updatedAt, @NonNull String draft) {
        this.id = id;
        this.title = title;
        this.pinned = pinned;
        this.deleted = deleted;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.draft = draft;
    }
}
