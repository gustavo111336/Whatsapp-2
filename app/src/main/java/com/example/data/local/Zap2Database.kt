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
        LocalContactEntity::class
    ],
    version = 2,
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

        @Volatile
        private var INSTANCE: Zap2Database? = null

        fun getInstance(context: Context): Zap2Database {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    Zap2Database::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2)
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
