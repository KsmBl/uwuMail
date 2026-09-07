package de.uwumail.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import de.uwumail.UwuMailApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the buttons on a new-mail notification.
 *
 * The whole point of the rules engine is to deal with mail before it asks for
 * attention; being able to finish the job from the shade is the end of that
 * idea. Work goes to the application scope rather than the receiver's, which
 * ends the moment onReceive returns.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? UwuMailApp ?: return
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        if (messageId <= 0) return
        val action = intent.action ?: return

        // Typed into the shade rather than tapped; empty means the reply was
        // abandoned, and the notification should stay where it is.
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)?.toString()?.trim()
        if (action == ACTION_REPLY && reply.isNullOrEmpty()) return

        // The notification goes now: waiting for the server would leave a
        // notification that has visibly done nothing.
        app.container.notifier.cancel(messageId)

        val pending = goAsync()
        app.appScope.launch(Dispatchers.IO) {
            try {
                val sync = app.container.syncManager
                val ids = listOf(messageId)
                runCatching {
                    when (action) {
                        ACTION_READ -> sync.setSeen(ids, true)
                        // Nobody is looking at a snackbar, so there is no undo
                        // to offer and nothing is gained by holding it back.
                        ACTION_ARCHIVE -> sync.archive(ids, allowUndo = false)
                        ACTION_TRASH -> sync.moveToTrash(ids, allowUndo = false)
                        ACTION_REPLY -> {
                            val accountId = app.container.db.messageDao()
                                .get(messageId)?.accountId ?: 0L
                            val sent = sync.queueReply(messageId, reply.orEmpty())
                            app.container.notifier.postReplyOutcome(accountId, sent)
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_READ = "de.uwumail.action.MARK_READ"
        const val ACTION_ARCHIVE = "de.uwumail.action.ARCHIVE"
        const val ACTION_TRASH = "de.uwumail.action.TRASH"
        const val ACTION_REPLY = "de.uwumail.action.REPLY"
        const val EXTRA_MESSAGE_ID = "messageId"

        /** Where the shade puts the text that was typed into the reply box. */
        const val KEY_REPLY = "reply_text"
    }
}
