package com.fifteencode.android;

import androidx.room.Embedded;
import androidx.room.Relation;

import java.util.List;

public class ConversationWithMessages {
    @Embedded public ConversationEntity conversation;
    @Relation(parentColumn = "id", entityColumn = "conversationId")
    public List<MessageEntity> messages;
}
