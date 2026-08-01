package com.fifteencode.android;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

@Dao
public interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void saveConversation(ConversationEntity conversation);

    @Insert
    void saveMessages(List<MessageEntity> messages);

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    void deleteMessages(String conversationId);

    @Transaction
    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    ConversationWithMessages getConversation(String id);

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
