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
    version = 1,
    exportSchema = false
)
abstract class Zap2Database : RoomDatabase() {
    abstract fun zap2Dao(): Zap2Dao

    suspend fun flushWalCheckpoint() {
        zap2Dao().checkpoint(SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
    }

    companion object {
        const val DB_NAME = "zap2_local.db"

        @Volatile
        private var INSTANCE: Zap2Database? = null

        fun getInstance(context: Context): Zap2Database {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    Zap2Database::class.java,
                    DB_NAME
                )
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
