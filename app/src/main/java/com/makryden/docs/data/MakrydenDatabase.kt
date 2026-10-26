package com.makryden.docs.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ItemEntity::class, SyncOperationEntity::class],
    version = 1,
    exportSchema = false
)
abstract class MakrydenDatabase : RoomDatabase() {

    abstract fun itemDao(): ItemDao

    abstract fun syncQueueDao(): SyncQueueDao

    companion object {
        @Volatile
        private var instance: MakrydenDatabase? = null

        fun getInstance(context: Context): MakrydenDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MakrydenDatabase::class.java,
                    "makryden-docs.db"
                ).build().also { instance = it }
            }
        }
    }
}
