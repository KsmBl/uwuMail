package de.uwumail.notify

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.uwumail.R
import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.MessageEntity

/**
 * Posts new-mail notifications, one group per account.
 *
 * Rules choose the channel: the default one, a silent low-importance one, or a
 * high-importance one. "Don't notify" never reaches here at all.
 */
class Notifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels(accounts: List<AccountEntity>) {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.channel_background),
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = context.getString(R.string.channel_background_desc) }
        )
        accounts.forEach { account ->
            system.createNotificationChannelGroup(
                NotificationChannelGroup(groupId(account.id), account.displayName)
            )
            listOf(
                Triple(defaultChannel(account.id), context.getString(R.string.channel_default), NotificationManager.IMPORTANCE_DEFAULT),
                Triple(silentChannel(account.id), context.getString(R.string.channel_silent), NotificationManager.IMPORTANCE_LOW),
                Triple(highChannel(account.id), context.getString(R.string.channel_high), NotificationManager.IMPORTANCE_HIGH)
            ).forEach { (id, name, importance) ->
                system.createNotificationChannel(
                    NotificationChannel(id, name, importance).apply {
                        group = groupId(account.id)
                        if (importance == NotificationManager.IMPORTANCE_LOW) {
                            setSound(null, null)
                            enableVibration(false)
                        }
                    }
                )
            }
        }
    }

    fun removeAccountChannels(accountId: Long) {
        val system = context.getSystemService(NotificationManager::class.java)
        listOf(defaultChannel(accountId), silentChannel(accountId), highChannel(accountId))
            .forEach { runCatching { system.deleteNotificationChannel(it) } }
        runCatching { system.deleteNotificationChannelGroup(groupId(accountId)) }
    }

    fun notifyNewMail(
        account: AccountEntity,
        message: MessageEntity,
        priority: NotificationPriority
    ) {
        if (!manager.areNotificationsEnabled()) return
        val channel = when (priority) {
            NotificationPriority.SILENT -> silentChannel(account.id)
            NotificationPriority.HIGH -> highChannel(account.id)
            NotificationPriority.DEFAULT -> defaultChannel(account.id)
        }
        val sender = message.fromName?.takeIf { it.isNotBlank() }
            ?: message.fromAddress
            ?: context.getString(R.string.unknown_sender_notif)

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(sender)
            .setContentText(message.subject.ifBlank { context.getString(R.string.no_subject_notif) })
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(sender)
                    .bigText(
                        buildString {
                            append(message.subject.ifBlank { context.getString(R.string.no_subject_notif) })
                            if (message.preview.isNotBlank()) append("\n").append(message.preview)
                        }
                    )
            )
            .setSubText(account.displayName)
            .setWhen(message.receivedAt)
            .setAutoCancel(true)
            .setGroup(groupId(account.id))
            .setContentIntent(openMessageIntent(message.id))
            .setPriority(
                when (priority) {
                    NotificationPriority.HIGH -> NotificationCompat.PRIORITY_HIGH
                    NotificationPriority.SILENT -> NotificationCompat.PRIORITY_LOW
                    NotificationPriority.DEFAULT -> NotificationCompat.PRIORITY_DEFAULT
                }
            )
            .setSilent(priority == NotificationPriority.SILENT)
            .addAction(
                R.drawable.ic_stat_mail,
                context.getString(R.string.notif_action_read),
                actionIntent(message.id, NotificationActionReceiver.ACTION_READ)
            )
            .addAction(
                R.drawable.ic_stat_mail,
                context.getString(R.string.notif_action_archive),
                actionIntent(message.id, NotificationActionReceiver.ACTION_ARCHIVE)
            )
            .addAction(
                R.drawable.ic_stat_mail,
                context.getString(R.string.notif_action_trash),
                actionIntent(message.id, NotificationActionReceiver.ACTION_TRASH)
            )
            .build()

        runCatching { manager.notify(message.id.toInt(), notification) }
    }

    fun postSummary(account: AccountEntity, newCount: Int) {
        if (newCount <= 0 || !manager.areNotificationsEnabled()) return
        val summary = NotificationCompat.Builder(context, defaultChannel(account.id))
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(account.displayName)
            .setContentText(
                context.resources.getQuantityString(R.plurals.new_messages, newCount, newCount)
            )
            .setGroup(groupId(account.id))
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(openAccountIntent(account.id))
            .build()
        runCatching { manager.notify(SUMMARY_BASE + account.id.toInt(), summary) }
    }

    fun cancel(messageId: Long) = manager.cancel(messageId.toInt())

    /**
     * One button on a notification.
     *
     * The request code mixes the message with the action, so the three buttons
     * on one notification do not collide and neither do two notifications.
     */
    private fun actionIntent(messageId: Long, action: String): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(NotificationActionReceiver.EXTRA_MESSAGE_ID, messageId)
        }
        return PendingIntent.getBroadcast(
            context,
            (messageId.toInt() * 31) + action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openMessageIntent(messageId: Long): PendingIntent {
        val intent = Intent(context, de.uwumail.ui.MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("messageId", messageId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, messageId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAccountIntent(accountId: Long): PendingIntent {
        val intent = Intent(context, de.uwumail.ui.MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("accountId", accountId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, SUMMARY_BASE + accountId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val CHANNEL_SERVICE = "background_sync"
        private const val SUMMARY_BASE = 1_000_000

        fun groupId(accountId: Long) = "account_$accountId"
        fun defaultChannel(accountId: Long) = "acct_${accountId}_default"
        fun silentChannel(accountId: Long) = "acct_${accountId}_silent"
        fun highChannel(accountId: Long) = "acct_${accountId}_high"
    }
}

enum class NotificationPriority { SILENT, DEFAULT, HIGH }
