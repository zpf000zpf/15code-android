package com.fifteencode.android;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {ConversationEntity.class, MessageEntity.class}, version = 1, exportSchema = false)
public abstract class ChatDatabase extends RoomDatabase {
    private static volatile ChatDatabase instance;
    public abstract ChatDao chatDao();

    static ChatDatabase get(Context context) {
        if (instance == null) {
            synchronized (ChatDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(),
                            ChatDatabase.class, "15code-chat.db").build();
                }
            }
        }
        return instance;
    }
}
