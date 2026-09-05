package de.uwumail.mail

/** A folder as the IMAP server describes it. */
data class RemoteFolder(
    val path: String,
    val displayName: String,
    val delimiter: String,
    val selectable: Boolean,
    val subscribed: Boolean,
    /** Guessed role from name + RFC 6154 SPECIAL-USE attributes. */
    val type: String
)

/** Envelope-level data for one message, enough to list it and run rules on it. */
data class FetchedMessage(
    val uid: Long,
    val messageIdHeader: String?,
    val subject: String,
    val fromName: String?,
    val fromAddress: String?,
    val to: List<String>,
    val cc: List<String>,
    val bcc: List<String>,
    val replyTo: String?,
    val sentAt: Long,
    val receivedAt: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val answered: Boolean,
    val draft: Boolean,
    val sizeBytes: Long,
    val likelyHasAttachments: Boolean,
    val headers: Map<String, List<String>>
)

data class FetchedPart(
    val partId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val isInline: Boolean,
    val contentId: String?
)

data class FetchedBody(
    val plain: String?,
    val html: String?,
    val attachments: List<FetchedPart>
)

data class FolderStatus(
    val uidValidity: Long,
    val uidNext: Long,
    val total: Int,
    val unread: Int
)

class MailException(message: String, cause: Throwable? = null) : Exception(message, cause)
