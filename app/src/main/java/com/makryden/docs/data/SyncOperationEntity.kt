package com.makryden.docs.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One queued offline save, to be replayed against Supabase when the
 * device is back online. Phase 0 queues updates only; creates and
 * deletes are not queued.
 *
 * opType is "UPDATE_DOC" for kind "doc" and "UPDATE_NOTE" for kind "note".
 */
@Entity(tableName = "sync_queue")
data class SyncOperationEntity(
    @PrimaryKey(autoGenerate = true)
    val opId: Long = 0,
    val itemId: String,
    val kind: String,
    val opType: String,
    val title: String,
    val content: String,
    val createdAt: Long
)
