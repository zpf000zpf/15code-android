package com.fifteencode.android;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {ConversationEntity.class, MessageEntity.class, ImageVersionEntity.class}, version = 3, exportSchema = false)
public abstract class ChatDatabase extends RoomDatabase {
    private static volatile ChatDatabase instance;
    public abstract ChatDao chatDao();

    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `image_versions` (`id` TEXT NOT NULL, `conversationId` TEXT, `parentVersionId` TEXT, `operation` TEXT, `status` TEXT, `prompt` TEXT, `localPath` TEXT, `thumbnailPath` TEXT, `mimeType` TEXT, `size` TEXT, `quality` TEXT, `format` TEXT, `clientRequestId` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_image_versions_conversationId` ON `image_versions` (`conversationId`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_image_versions_parentVersionId` ON `image_versions` (`parentVersionId`)");
        }
    };

    private static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE `image_versions` ADD COLUMN `completedAt` INTEGER NOT NULL DEFAULT 0");
        }
    };

    static ChatDatabase get(Context context) {
        if (instance == null) {
            synchronized (ChatDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(),
                            ChatDatabase.class, "15code-chat.db")
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build();
                }
            }
        }
        return instance;
    }
}
