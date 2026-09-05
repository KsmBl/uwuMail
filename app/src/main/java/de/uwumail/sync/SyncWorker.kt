package de.uwumail.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.uwumail.UwuMailApp

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as UwuMailApp).container
        val accountId = inputData.getLong(KEY_ACCOUNT_ID, -1L)
        return try {
            if (accountId > 0) {
                container.syncManager.syncAccount(accountId)
                container.syncManager.sendOutbox()
            } else {
                container.syncManager.syncAll()
            }
            Result.success()
        } catch (e: Throwable) {
            // Transient network and server hiccups are the common case here.
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        const val KEY_ACCOUNT_ID = "accountId"
    }
}
