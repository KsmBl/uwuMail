package de.uwumail.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val email: String,
    val color: Int,

    val imapHost: String,
    val imapPort: Int,
    val imapSecurity: String,
    val imapUsername: String,

    val smtpHost: String,
    val smtpPort: Int,
    val smtpSecurity: String,
    val smtpUsername: String,

    /** PASSWORD or OAUTH2; see [de.uwumail.mail.oauth.AuthType]. */
    val authType: String = "PASSWORD",
    /** Provider id when [authType] is OAUTH2, e.g. GOOGLE. */
    val oauthProvider: String? = null,

    /** Accept any server certificate. Needed for self-signed setups; off by default. */
    val trustAllCerts: Boolean = false,
    /** Send MAIL FROM matching the chosen identity instead of the account address. */
    val useIdentityAsEnvelopeSender: Boolean = true,

    val syncEnabled: Boolean = true,
    val syncIntervalMinutes: Int = 15,
    /**
     * Keep an IMAP IDLE connection open in a foreground service, so new mail
     * arrives without the app being opened. On by default: WorkManager's
     * 15-minute floor plus Doze makes polling alone useless for notifications.
     */
    val pushEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,

    /** Appended to new messages composed from this account. */
    val signature: String? = null,

    val archiveFolder: String? = null,
    val trashFolder: String? = null,
    val sentFolder: String? = null,
    val draftsFolder: String? = null,

    val position: Int = 0
)

@Entity(
    tableName = "identities",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE
    )],
    // Emails are stored lowercased, so this also rules out case-only duplicates.
    indices = [Index(value = ["accountId", "email"], unique = true)]
)
data class IdentityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val displayName: String,
    /** Arbitrary address to put in the From header; the server decides if it is allowed. */
    val email: String,
    val replyTo: String? = null,
    val signature: String? = null,
    val isDefault: Boolean = false
)

@Entity(
    tableName = "folders",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["accountId", "path"], unique = true),
        Index("accountId")
    ]
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    /** IMAP path for remote folders; `local/<name>` for device-only folders. */
    val path: String,
    val displayName: String,
    val delimiter: String = "/",
    val type: String = "CUSTOM",
    val isLocal: Boolean = false,
    val selectable: Boolean = true,
    val subscribed: Boolean = true,
    val syncEnabled: Boolean = true,
    val uidValidity: Long = 0,
    val highestUid: Long = 0,
    val unreadCount: Int = 0,
    val totalCount: Int = 0,
    /** Automatic order from [de.uwumail.mail.FolderClassifier]. */
    val position: Int = 0,
    /** Set once the user drags a folder about; overrides [position] from then on. */
    val sortOverride: Int? = null,
    /** Hidden from the folder list on this device only; untouched on the server. */
    val hidden: Boolean = false
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = FolderEntity::class,
        parentColumns = ["id"],
        childColumns = ["folderId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["folderId", "uid"], unique = true),
        Index("accountId"),
        Index("folderId"),
        Index("receivedAt"),
        Index("messageIdHeader"),
        Index("senderDomain")
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val folderId: Long,
    /** IMAP UID, or a negative synthetic id for local-only messages. */
    val uid: Long,
    val messageIdHeader: String? = null,

    val subject: String = "",
    val fromName: String? = null,
    val fromAddress: String? = null,
    /** Lowercased domain of [fromAddress], indexed so blocklists can be matched cheaply. */
    val senderDomain: String? = null,
    val toList: String = "",
    val ccList: String = "",
    val bccList: String = "",
    val replyTo: String? = null,

    val sentAt: Long = 0,
    val receivedAt: Long = 0,

    val seen: Boolean = false,
    val flagged: Boolean = false,
    val answered: Boolean = false,
    val draft: Boolean = false,

    val hasAttachments: Boolean = false,
    val sizeBytes: Long = 0,

    val preview: String = "",
    val bodyPlain: String? = null,
    val bodyHtml: String? = null,
    val bodyDownloaded: Boolean = false,

    /** All headers as a JSON object of name -> array of values, for rule matching. */
    val headersJson: String? = null,
    /** Path of the downloaded .eml on disk, if any. */
    val rawFilePath: String? = null,

    val isLocal: Boolean = false,
    val notified: Boolean = false,
    val rulesApplied: Boolean = false,
    /**
     * Hidden from every list because a delete/move is in flight. The row is
     * kept so it can be put back if the server refuses.
     */
    val pendingRemoval: Boolean = false
)

@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(
        entity = MessageEntity::class,
        parentColumns = ["id"],
        childColumns = ["messageId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("messageId")]
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val partId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val isInline: Boolean = false,
    val contentId: String? = null,
    val localPath: String? = null
)

@Entity(tableName = "rules", indices = [Index("priority")])
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    /** Lower runs first. */
    val priority: Int = 100,
    /** null means the rule applies to every account. */
    val accountId: Long? = null,
    /** null means every folder; otherwise an IMAP path. */
    val folderPath: String? = null,
    val matchMode: String = "ALL",
    /** Stop evaluating later rules once this one matches. */
    val stopProcessing: Boolean = false,
    val createdAt: Long = 0,
    val lastMatchedAt: Long? = null,
    val matchCount: Int = 0
)

@Entity(
    tableName = "rule_conditions",
    foreignKeys = [ForeignKey(
        entity = RuleEntity::class,
        parentColumns = ["id"],
        childColumns = ["ruleId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("ruleId")]
)
data class RuleConditionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val field: String,
    /** Header name when [field] is HEADER. */
    val headerName: String? = null,
    val operator: String,
    val value: String,
    val caseSensitive: Boolean = false,
    val negate: Boolean = false
)

@Entity(
    tableName = "rule_actions",
    foreignKeys = [ForeignKey(
        entity = RuleEntity::class,
        parentColumns = ["id"],
        childColumns = ["ruleId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("ruleId")]
)
data class RuleActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val type: String,
    /** Target folder path for move/copy actions. */
    val stringArg: String? = null,
    val orderIndex: Int = 0
)

/** Audit trail so a rule that eats mail can be explained after the fact. */
@Entity(tableName = "rule_log", indices = [Index("at")])
data class RuleLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val ruleName: String,
    val accountId: Long,
    val subject: String,
    val fromAddress: String?,
    val actionsTaken: String,
    val at: Long
)

/** A queued outgoing action, retried when the account is reachable again. */
@Entity(tableName = "outbox", indices = [Index("accountId")])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val identityId: Long?,
    val fromAddress: String,
    val fromName: String,
    val to: String,
    val cc: String,
    val bcc: String,
    val replyTo: String?,
    val subject: String,
    val bodyPlain: String,
    val bodyHtml: String?,
    val attachmentPaths: String = "",
    val inReplyTo: String? = null,
    val references: String? = null,
    val createdAt: Long,
    val lastError: String? = null,
    val attempts: Int = 0
)

/**
 * A list of sender domains treated as spam.
 *
 * Built-in lists point at a public URL and are refreshed on demand; the one with
 * a null [url] is the user's own list of blocked senders.
 */
@Entity(tableName = "blocklists")
data class BlocklistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val url: String? = null,
    val enabled: Boolean = false,
    val builtIn: Boolean = false,
    val entryCount: Int = 0,
    val updatedAt: Long = 0,
    val lastError: String? = null,
    val position: Int = 0
)

@Entity(
    tableName = "blocklist_entries",
    foreignKeys = [ForeignKey(
        entity = BlocklistEntity::class,
        parentColumns = ["id"],
        childColumns = ["listId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("pattern"), Index("listId")]
)
data class BlocklistEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    /** A lowercased domain, or a full address for a manually blocked sender. */
    val pattern: String
)
