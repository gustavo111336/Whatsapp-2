package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SimpleSQLiteQuery

@Database(
    entities = [
        LocalChatEntity::class,
        LocalMessageEntity::class,
        LocalContactEntity::class,
        SavedStickerGifEntity::class,
        ScheduledMessageEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class Zap2Database : RoomDatabase() {
    abstract fun zap2Dao(): Zap2Dao

    suspend fun flushWalCheckpoint() {
        zap2Dao().checkpoint(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
    }

    companion object {
        const val DB_NAME = "zap2_local.db"

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_messages ADD COLUMN fileName TEXT")
                db.execSQL("ALTER TABLE local_messages ADD COLUMN fileSizeBytes INTEGER")
                db.execSQL("ALTER TABLE local_messages ADD COLUMN mimeType TEXT")
                db.execSQL("ALTER TABLE local_messages ADD COLUMN edited INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_contacts ADD COLUMN username TEXT")
            }
        }

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_messages ADD COLUMN audioUrl TEXT")
                db.execSQL("ALTER TABLE local_messages ADD COLUMN audioMimeType TEXT")
                db.execSQL("ALTER TABLE local_messages ADD COLUMN localAudioPath TEXT")
                db.execSQL("ALTER TABLE local_messages ADD COLUMN viewOnce INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE local_messages ADD COLUMN viewedByJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_stickers_gifs` (
                        `id` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `localFilePath` TEXT NOT NULL,
                        `localAudioPath` TEXT,
                        `audioDurationMs` INTEGER NOT NULL,
                        `remoteMediaUrl` TEXT,
                        `remoteAudioUrl` TEXT,
                        `createdAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scheduled_messages` (
                        `id` TEXT NOT NULL,
                        `chatId` TEXT NOT NULL,
                        `senderUid` TEXT NOT NULL,
                        `senderName` TEXT NOT NULL,
                        `chatTitle` TEXT NOT NULL,
                        `messageType` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `localMediaPath` TEXT,
                        `localAudioPath` TEXT,
                        `mediaDurationMs` INTEGER NOT NULL,
                        `mimeType` TEXT,
                        `viewOnce` INTEGER NOT NULL,
                        `scheduledAtMs` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `errorMessage` TEXT,
                        `createdAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_messages ADD COLUMN aiGenerated INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE local_messages ADD COLUMN aiPersona TEXT")
                db.execSQL("ALTER TABLE local_messages ADD COLUMN aiModel TEXT")
            }
        }

        @Volatile
        private var INSTANCE: Zap2Database? = null

        fun getInstance(context: Context): Zap2Database {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    Zap2Database::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        fun closeForBackupRestore() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
