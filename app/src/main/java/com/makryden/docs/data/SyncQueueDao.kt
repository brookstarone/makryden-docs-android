package com.makryden.docs.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SyncQueueDao {

    @Insert
    fun insert(op: SyncOperationEntity): Long

    @Query("SELECT * FROM sync_queue ORDER BY createdAt ASC")
    fun getPending(): List<SyncOperationEntity>

    @Query("DELETE FROM sync_queue WHERE opId = :opId")
    fun deleteById(opId: Long)

    @Query("SELECT COUNT(*) FROM sync_queue")
    fun countPending(): Int
}
