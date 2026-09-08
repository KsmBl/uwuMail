package de.uwumail.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import de.uwumail.data.db.AccountEntity
import java.util.concurrent.TimeUnit

/**
 * Periodic background sync, one WorkManager chain per account.
 *
 * WorkManager clamps periodic work to 15 minutes; accounts that want faster
 * updates need push ([PushService]) rather than a shorter interval.
 */
object SyncScheduler {

    private const val PERIODIC_PREFIX = "sync_account_"
    private const val ONE_SHOT_PREFIX = "sync_now_"

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedule(context: Context, account: AccountEntity) {
        val manager = WorkManager.getInstance(context)
        if (!account.syncEnabled) {
            manager.cancelUniqueWork(PERIODIC_PREFIX + account.id)
            return
        }
        val interval = account.syncIntervalMinutes.coerceAtLeast(15).toLong()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(periodicInput(account.id))
            .build()
        manager.enqueueUniquePeriodicWork(
            PERIODIC_PREFIX + account.id,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun scheduleAll(context: Context, accounts: List<AccountEntity>) {
        accounts.forEach { schedule(context, it) }
    }

    fun cancel(context: Context, accountId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_PREFIX + accountId)
    }

    /** What the app checks on its own, which the hours set for checking govern. */
    fun periodicInput(accountId: Long): Data =
        Data.Builder().putLong(SyncWorker.KEY_ACCOUNT_ID, accountId).build()

    /** What somebody asked for, which they do not. */
    fun askedForInput(accountId: Long): Data = Data.Builder()
        .putLong(SyncWorker.KEY_ACCOUNT_ID, accountId)
        .putBoolean(SyncWorker.KEY_USER_ASKED, true)
        .build()

    /**
     * Fetches mail because somebody asked for it — Sync now, or a freshly added
     * account.
     *
     * Marked as asked for, so the hours set for checking do not apply: those
     * govern what the app does on its own, and this is not that. Still routed
     * through WorkManager so it survives the screen it was started from.
     */
    fun syncNow(context: Context, accountId: Long = -1L) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInputData(askedForInput(accountId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_PREFIX + accountId,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
