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
            .setInputData(Data.Builder().putLong(SyncWorker.KEY_ACCOUNT_ID, account.id).build())
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

    /** Fire-and-forget sync used by pull-to-refresh and after sending. */
    fun syncNow(context: Context, accountId: Long = -1L) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInputData(Data.Builder().putLong(SyncWorker.KEY_ACCOUNT_ID, accountId).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_PREFIX + accountId,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
