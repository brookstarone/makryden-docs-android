package com.makryden.docs.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached list metadata for one document, note, or PDF, plus full content
 * for docs and notes so offline saves can be queued.
 *
 * Phase 0 caches list metadata only for PDFs; content is stored for docs
 * and notes when the caller supplies it via DocsRepository.cacheItem.
 */
@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey
    val id: String,
    val kind: String,
    val title: String,
    val snippet: String,
    val updatedAt: String,
    val content: String = "",
    val updatedAtLocal: Long = 0
)
