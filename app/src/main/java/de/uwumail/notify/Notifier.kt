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
                "Background sync",
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = "Keeps push connections alive" }
        )
        accounts.forEach { account ->
            system.createNotificationChannelGroup(
                NotificationChannelGroup(groupId(account.id), account.displayName)
            )
            listOf(
                Triple(defaultChannel(account.id), "New mail", NotificationManager.IMPORTANCE_DEFAULT),
                Triple(silentChannel(account.id), "New mail (silent)", NotificationManager.IMPORTANCE_LOW),
                Triple(highChannel(account.id), "New mail (priority)", NotificationManager.IMPORTANCE_HIGH)
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
            ?: "Unknown sender"

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(sender)
            .setContentText(message.subject.ifBlank { "(no subject)" })
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(sender)
                    .bigText(
                        buildString {
                            append(message.subject.ifBlank { "(no subject)" })
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
            .build()

        runCatching { manager.notify(message.id.toInt(), notification) }
    }

    fun postSummary(account: AccountEntity, newCount: Int) {
        if (newCount <= 0 || !manager.areNotificationsEnabled()) return
        val summary = NotificationCompat.Builder(context, defaultChannel(account.id))
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(account.displayName)
            .setContentText("$newCount new message${if (newCount == 1) "" else "s"}")
            .setGroup(groupId(account.id))
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(openAccountIntent(account.id))
            .build()
        runCatching { manager.notify(SUMMARY_BASE + account.id.toInt(), summary) }
    }

    fun cancel(messageId: Long) = manager.cancel(messageId.toInt())

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
