package de.uwumail.mail

import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.MessageEntity
import de.uwumail.data.db.OutboxEntity

/**
 * Turns a message and a line of text into a reply ready for the outbox.
 *
 * Kept away from the composer because the shade needs it too, and a reply typed
 * into a notification has no screen to hold anything: it is built, queued and
 * sent by machinery that may be running with no activity alive at all.
 */
object ReplyDraft {

    /**
     * [ownAddresses] are every address this mailbox answers to, lowercased.
     * The reply goes out from whichever of them the original was addressed to,
     * so an alias keeps its identity through the thread, and from the account's
     * own address when none of them matches.
     */
    fun build(
        account: AccountEntity,
        message: MessageEntity,
        ownAddresses: List<String>,
        headers: Map<String, List<String>>,
        body: String,
        now: Long
    ): OutboxEntity {
        val recipient = message.replyTo?.takeIf { it.isNotBlank() }
            ?: message.fromAddress.orEmpty()
        val from = message.toList.split(',')
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it.lowercase() in ownAddresses }
            ?: account.email

        return OutboxEntity(
            accountId = account.id,
            identityId = null,
            fromAddress = from,
            fromName = account.displayName,
            to = recipient,
            cc = "",
            bcc = "",
            replyTo = null,
            subject = subjectFor(message.subject),
            bodyPlain = body,
            bodyHtml = null,
            inReplyTo = message.messageIdHeader,
            references = listOfNotNull(
                headers["references"]?.firstOrNull()?.takeIf { it.isNotBlank() },
                message.messageIdHeader
            ).joinToString(" ").ifBlank { null },
            createdAt = now,
            // Nothing was written in a draft, and the original is flagged
            // answered once the reply has actually gone.
            draftMessageId = null,
            answeringMessageId = message.id
        )
    }

    /** "Re:" once, however many times the thread has been round already. */
    fun subjectFor(subject: String): String =
        if (subject.trim().startsWith("Re:", ignoreCase = true)) subject
        else ("Re: " + subject).trim()
}
