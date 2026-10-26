package com.makryden.docs.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.makryden.docs.SessionStore
import com.makryden.docs.SupabaseClient
import com.makryden.docs.data.DocsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drains the offline sync queue: every queued doc/note update is
 * replayed against Supabase in createdAt order (takePending already
 * orders ASC, so the latest edit is last and last write wins on
 * the server). Runs only when network is available (see
 * SyncScheduler constraints).
 *
 * Privacy: logs itemIds only, never titles or content.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val repo = DocsRepository(applicationContext)
            val ops = repo.takePending()
            if (ops.isEmpty()) {
                return@withContext Result.success()
            }
            val token = try {
                SessionStore(applicationContext).getValidAccessTokenBlocking()
            } catch (e: Exception) {
                Log.w(TAG, "token refresh failed", e)
                null
            }
            if (token == null) {
                // Keep the queue intact; WorkManager retries later.
                return@withContext Result.retry()
            }
            for (op in ops) {
                val synced = try {
                    when (op.opType) {
                        "UPDATE_DOC" -> SupabaseClient.updateDocument(op.itemId, token, op.title, op.content)
                        "UPDATE_NOTE" -> SupabaseClient.updateNote(op.itemId, token, op.title, op.content)
                        else -> {
                            // Unknown op type: drop it so one bad row can
                            // never block the queue forever.
                            Log.w(TAG, "unknown opType, dropping op ${op.opId}")
                            repo.removeOp(op.opId)
                            continue
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "sync failed for ${op.opType} ${op.itemId}", e)
                    return@withContext Result.retry()
                }
                if (synced) {
                    repo.removeOp(op.opId)
                    Log.i(TAG, "synced ${op.opType} ${op.itemId}")
                } else {
                    Log.w(TAG, "sync failed for ${op.opType} ${op.itemId}, will retry")
                    return@withContext Result.retry()
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "sync worker failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "DocsSync"
    }
}
