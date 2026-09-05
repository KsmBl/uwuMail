package de.uwumail.sync

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import de.uwumail.R
import de.uwumail.UwuMailApp
import de.uwumail.core.FolderType
import de.uwumail.mail.ImapClient
import de.uwumail.notify.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds an IMAP IDLE connection open per push-enabled account so new mail lands
 * immediately instead of at the next 15-minute WorkManager tick.
 *
 * IDLE is a blocking call, so each account gets its own connection and its own
 * IO coroutine, separate from the pooled connections the rest of the app uses.
 */
class PushService : LifecycleService() {

    private val jobs = mutableMapOf<Long, Job>()

    override fun onCreate() {
        super.onCreate()
        startForeground()
        lifecycleScope.launch { startWatchers() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun startForeground() {
        val notification: Notification =
            NotificationCompat.Builder(this, Notifier.CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_stat_mail)
                .setContentTitle("uwuMail")
                .setContentText("Watching for new mail")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else 0
        )
    }

    private suspend fun startWatchers() {
        val container = (application as UwuMailApp).container
        val accounts = container.db.accountDao().getAll().filter { it.pushEnabled }
        if (accounts.isEmpty()) {
            stopSelf()
            return
        }
        accounts.forEach { account ->
            jobs[account.id] = lifecycleScope.launch(Dispatchers.IO) {
                var backoffSeconds = 5L
                while (isActive) {
                    var client: ImapClient? = null
                    try {
                        val inbox = container.db.folderDao()
                            .forAccount(account.id)
                            .firstOrNull { it.type == FolderType.INBOX.name }
                            ?: run { delay(60_000); return@launch }

                        client = container.imapPool.newClient(account.id)
                        backoffSeconds = 5
                        while (isActive) {
                            // Blocks until the server reports a change on the mailbox.
                            client.idle(inbox.path)
                            withContext(Dispatchers.IO) {
                                runCatching { container.syncManager.syncFolder(inbox.id) }
                            }
                        }
                    } catch (e: Throwable) {
                        if (!isActive) return@launch
                        delay(backoffSeconds * 1000)
                        backoffSeconds = (backoffSeconds * 2).coerceAtMost(300)
                    } finally {
                        runCatching { client?.close() }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, PushService::class.java)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PushService::class.java)) }
        }

        /** Restarts the service so the watcher set matches the current accounts. */
        fun restart(context: Context, anyPushEnabled: Boolean) {
            stop(context)
            if (anyPushEnabled) start(context)
        }
    }
}
