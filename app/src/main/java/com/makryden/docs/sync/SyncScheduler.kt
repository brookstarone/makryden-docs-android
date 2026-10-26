package com.makryden.docs.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Schedules a one-shot drain of the offline sync queue.
 * KEEP policy: if a sync is already queued or running, a second
 * enqueue is a no-op instead of stacking duplicate workers.
 */
object SyncScheduler {

    fun enqueuePending(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("docs-sync", ExistingWorkPolicy.KEEP, request)
    }
}
