package de.uwumail.sync

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import de.uwumail.R
import de.uwumail.UwuMailApp
import de.uwumail.core.FolderType
import de.uwumail.data.settings.SettingsStore
import de.uwumail.mail.ImapClient
import de.uwumail.notify.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps an IMAP IDLE connection open per account so new mail arrives without the
 * app being opened — the same approach Thunderbird and K-9 take, because IMAP
 * has no push service to delegate to the way FCM-based messengers do.
 *
 * IDLE is a blocking call, so each account gets its own connection and its own
 * IO coroutine, separate from the pooled connections the rest of the app uses.
 */
class PushService : LifecycleService() {

    private val jobs = mutableMapOf<Long, Job>()
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null

    /** Bumped when the network returns, so parked watchers stop waiting out a backoff. */
    private val networkGeneration = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        running = true
        startForeground()
        watchConnectivity()
        lifecycleScope.launch { startWatchers() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Restart with a null intent if the process is killed; the watchers are
        // rebuilt from the database in onCreate.
        return START_STICKY
    }

    private fun startForeground() {
        val notification: Notification =
            NotificationCompat.Builder(this, Notifier.CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_stat_mail)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.push_watching))
                .setOngoing(true)
                .setShowWhen(false)
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

    /**
     * A dropped connection leaves a watcher sleeping out its backoff. Waking on
     * the network coming back turns "mail arrives in five minutes" into
     * "mail arrives now".
     */
    private fun watchConnectivity() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkGeneration.set(true)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onSuccess { connectivityCallback = callback }
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
                var backoffSeconds = INITIAL_BACKOFF_SECONDS
                while (isActive) {
                    var client: ImapClient? = null
                    try {
                        // Holding IDLE open outside the hours the user set would
                        // be checking for mail, so the connection is dropped and
                        // the watcher waits for the window to come round.
                        waitForSyncWindow(container.settings)

                        val inbox = container.db.folderDao()
                            .forAccount(account.id)
                            .firstOrNull { it.type == FolderType.INBOX.name }
                        if (inbox == null) {
                            // Folders are not known yet; a sync will discover them.
                            runCatching { container.syncManager.refreshFolders(account.id) }
                            delay(30_000)
                            continue
                        }

                        client = container.imapPool.newClient(account.id, forIdle = true)
                        backoffSeconds = INITIAL_BACKOFF_SECONDS

                        while (isActive && container.settings.current
                                .syncAllowedAt(System.currentTimeMillis())
                        ) {
                            // Blocks until the server reports a change on the mailbox.
                            client.idle(inbox.path)
                            // Hold the CPU across the fetch: IDLE can return while the
                            // device is dozing, and the sync must not be suspended
                            // half way through.
                            withWakeLock {
                                runCatching { container.syncManager.syncFolder(inbox.id) }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        if (!isActive) return@launch
                        waitBeforeRetry(backoffSeconds)
                        backoffSeconds = (backoffSeconds * 2).coerceAtMost(MAX_BACKOFF_SECONDS)
                    } finally {
                        runCatching { client?.close() }
                    }
                }
            }
        }
    }

    /**
     * Blocks until background checks are allowed again.
     *
     * Polled rather than scheduled to the minute: the window can be edited, and
     * the clock can move under us across a DST change or a time-zone change.
     */
    private suspend fun waitForSyncWindow(settings: SettingsStore) {
        while (!settings.current.syncAllowedAt(System.currentTimeMillis())) {
            delay(WINDOW_POLL_MILLIS)
        }
    }

    /** Sleeps for [seconds], cutting it short as soon as a network comes back. */
    private suspend fun waitBeforeRetry(seconds: Long) {
        networkGeneration.set(false)
        var waited = 0L
        while (waited < seconds * 1000) {
            delay(POLL_INTERVAL_MILLIS)
            waited += POLL_INTERVAL_MILLIS
            if (networkGeneration.compareAndSet(true, false)) return
        }
    }

    private inline fun withWakeLock(block: () -> Unit) {
        val power = getSystemService(PowerManager::class.java)
        val lock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        runCatching { lock?.acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
        try {
            block()
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    override fun onDestroy() {
        running = false
        connectivityCallback?.let { callback ->
            runCatching {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
            }
        }
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val WAKE_LOCK_TAG = "uwuMail:push"
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 3 * 60 * 1000L
        private const val INITIAL_BACKOFF_SECONDS = 5L
        private const val MAX_BACKOFF_SECONDS = 300L
        private const val POLL_INTERVAL_MILLIS = 1_000L
        private const val WINDOW_POLL_MILLIS = 5 * 60 * 1000L

        /** Whether the service is currently up, for the settings screen to report. */
        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, PushService::class.java)
            // Starting a foreground service from the background is restricted on
            // Android 12+; the call sites here are all exempt (app foreground,
            // BOOT_COMPLETED, or an expedited worker).
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

        /** Starts the service if any account wants push and it is not already up. */
        fun ensureRunning(context: Context, anyPushEnabled: Boolean) {
            if (anyPushEnabled && !running) start(context)
        }
    }
}
