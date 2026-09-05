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
            // Watchdog: if the push service was killed and not restarted, this
            // brings it back. Only succeeds while the app is exempt from the
            // background foreground-service restriction, which is one more
            // reason the battery-optimisation exemption matters.
            val accounts = container.db.accountDao().getAll()
            PushService.ensureRunning(applicationContext, accounts.any { it.pushEnabled })
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
