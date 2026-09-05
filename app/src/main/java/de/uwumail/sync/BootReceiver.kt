package de.uwumail.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.uwumail.UwuMailApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? UwuMailApp ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val accounts = app.container.db.accountDao().getAll()
                SyncScheduler.scheduleAll(context, accounts)
                if (accounts.any { it.pushEnabled }) PushService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
