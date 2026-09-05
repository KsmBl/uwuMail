package de.uwumail.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.uwumail.UwuMailApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Brings background mail back after a reboot or an app update.
 *
 * Both wipe scheduled work and stop the push service, so without this the app
 * would go quiet until it was next opened by hand.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return
        val app = context.applicationContext as? UwuMailApp ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val accounts = app.container.db.accountDao().getAll()
                SyncScheduler.scheduleAll(context, accounts)
                PushService.ensureRunning(context, accounts.any { it.pushEnabled })
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}
