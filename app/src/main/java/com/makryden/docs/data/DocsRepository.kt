package com.makryden.docs.data

import android.content.Context
import com.makryden.docs.SupabaseClient

/**
 * Local-first data layer for cached items and the offline sync queue.
 *
 * All methods in this class are BLOCKING. They perform Room database
 * reads and writes on the calling thread, so callers MUST invoke them
 * from a background thread, never from the main thread. No method here
 * switches threads, schedules work, or performs network I/O.
 */
class DocsRepository(context: Context) {

    private val database: MakrydenDatabase = MakrydenDatabase.getInstance(context)
    private val itemDao: ItemDao = database.itemDao()
    private val syncQueueDao: SyncQueueDao = database.syncQueueDao()

    fun getCachedItems(kind: String): List<SupabaseClient.DocItem> {
        return itemDao.getItemsByKind(kind).map { entity ->
            SupabaseClient.DocItem(
                id = entity.id,
                title = entity.title,
                snippet = entity.snippet,
                updatedAt = entity.updatedAt,
                kind = entity.kind
            )
        }
    }

    fun cacheItems(kind: String, items: List<SupabaseClient.DocItem>) {
        val now = System.currentTimeMillis()
        val entities = items.map { item ->
            ItemEntity(
                id = item.id,
                kind = kind,
                title = item.title,
                snippet = item.snippet,
                updatedAt = item.updatedAt,
                content = "",
                updatedAtLocal = now
            )
        }
        itemDao.upsertItems(entities)
    }

    fun cacheItem(item: SupabaseClient.DocItem, content: String = "") {
        val entity = ItemEntity(
            id = item.id,
            kind = item.kind,
            title = item.title,
            snippet = item.snippet,
            updatedAt = item.updatedAt,
            content = content,
            updatedAtLocal = System.currentTimeMillis()
        )
        itemDao.upsertItem(entity)
    }

    fun queueUpdate(kind: String, itemId: String, title: String, content: String): Long {
        val opType = if (kind == "note") "UPDATE_NOTE" else "UPDATE_DOC"
        val op = SyncOperationEntity(
            itemId = itemId,
            kind = kind,
            opType = opType,
            title = title,
            content = content,
            createdAt = System.currentTimeMillis()
        )
        return syncQueueDao.insert(op)
    }

    fun pendingCount(): Int {
        return syncQueueDao.countPending()
    }

    fun takePending(): List<SyncOperationEntity> {
        return syncQueueDao.getPending()
    }

    fun removeOp(opId: Long) {
        syncQueueDao.deleteById(opId)
    }
}
