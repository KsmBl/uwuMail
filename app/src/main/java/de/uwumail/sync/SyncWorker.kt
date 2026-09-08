package de.uwumail.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.uwumail.UwuMailApp
import de.uwumail.core.SyncLog

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as UwuMailApp).container
        val accountId = inputData.getLong(KEY_ACCOUNT_ID, -1L)
        val userAsked = inputData.getBoolean(KEY_USER_ASKED, false)

        // Outside the hours the user set, unattended checks do not run at all.
        // The work is dropped rather than retried: by the next period it will
        // either be inside the window or still deliberately outside it.
        //
        // Somebody pressing Sync now, or adding an account, is not an
        // unattended check. Those went through here too and were dropped on the
        // same rule, so a mailbox added in the evening with a daytime window
        // stayed empty until morning with nothing to say why.
        val allowedNow = container.settings.current.syncAllowedAt(System.currentTimeMillis())
        if (!runsNow(userAsked, allowedNow)) {
            SyncLog.write("worker: outside the hours set for checking, doing nothing")
            return Result.success()
        }
        SyncLog.write(
            "worker: checking ${if (accountId > 0) "account $accountId" else "every account"}" +
                if (userAsked) " because it was asked for" else ""
        )

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
            SyncLog.failure("worker", e)
            // Transient network and server hiccups are the common case here.
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        const val KEY_ACCOUNT_ID = "accountId"

        /** Set when a person asked for this, which the checking hours do not govern. */
        const val KEY_USER_ASKED = "userAsked"

        /**
         * Whether this run goes ahead.
         *
         * The hours only ever governed unattended checking; a run somebody
         * asked for is not that, and used to be dropped on the same rule.
         */
        fun runsNow(userAsked: Boolean, allowedNow: Boolean): Boolean = userAsked || allowedNow
    }
}
