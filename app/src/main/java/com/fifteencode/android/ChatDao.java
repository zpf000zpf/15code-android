package com.fifteencode.android;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Upsert;

import java.util.List;

@Dao
public interface ChatDao {
    @Upsert
    void saveConversation(ConversationEntity conversation);

    @Insert
    void saveMessages(List<MessageEntity> messages);

    @Transaction
    default void replaceConversationMessages(ConversationEntity conversation, List<MessageEntity> messages) {
        ConversationEntity existing = getConversationEntity(conversation.id);
        if (existing != null) conversation.draft = existing.draft;
        saveConversation(conversation);
        deleteMessages(conversation.id);
        if (!messages.isEmpty()) saveMessages(messages);
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void saveImageVersion(ImageVersionEntity version);

    @Transaction
    default void saveImageVersionWithConversation(ImageVersionEntity version,
                                                  ConversationEntity conversation) {
        ConversationEntity existing = getConversationEntity(conversation.id);
        if (existing == null) {
            saveConversation(conversation);
        } else if ((existing.title == null || existing.title.isEmpty()
                || "新对话".equals(existing.title)) && !"新对话".equals(conversation.title)) {
            rename(existing.id, conversation.title, conversation.updatedAt);
        }
        saveImageVersion(version);
    }

    @Query("SELECT * FROM image_versions WHERE conversationId = :conversationId " +
            "ORDER BY createdAt DESC LIMIT :limit")
    List<ImageVersionEntity> listRecentImageVersions(String conversationId, int limit);

    @Query("SELECT * FROM image_versions WHERE id = :id LIMIT 1")
    ImageVersionEntity getImageVersion(String id);

    @Query("UPDATE image_versions SET status = :status, localPath = :localPath, " +
            "thumbnailPath = :thumbnailPath, mimeType = :mimeType, completedAt = :completedAt " +
            "WHERE id = :id")
    void completeImageVersion(String id, String status, String localPath, String thumbnailPath,
                              String mimeType, long completedAt);

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    void deleteMessages(String conversationId);

    @Transaction
    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    ConversationWithMessages getConversation(String id);

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    ConversationEntity getConversationEntity(String id);

    @Query("SELECT * FROM conversations WHERE deleted = 0 AND (:query = '' OR title LIKE '%' || :query || '%') ORDER BY pinned DESC, updatedAt DESC")
    List<ConversationEntity> listConversations(String query);

    @Query("SELECT * FROM conversations WHERE deleted = 1 ORDER BY updatedAt DESC")
    List<ConversationEntity> listDeleted();

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    void rename(String id, String title, long updatedAt);

    @Query("UPDATE conversations SET pinned = :pinned, updatedAt = :updatedAt WHERE id = :id")
    void setPinned(String id, boolean pinned, long updatedAt);

    @Query("UPDATE conversations SET deleted = :deleted, updatedAt = :updatedAt WHERE id = :id")
    void setDeleted(String id, boolean deleted, long updatedAt);

    @Query("UPDATE conversations SET draft = :draft, updatedAt = :updatedAt WHERE id = :id")
    void saveDraft(String id, String draft, long updatedAt);
}
