package de.uwumail.data.db

import androidx.room.Embedded
import androidx.room.Relation

data class RuleWithDetails(
    @Embedded val rule: RuleEntity,
    @Relation(parentColumn = "id", entityColumn = "ruleId")
    val conditions: List<RuleConditionEntity>,
    @Relation(parentColumn = "id", entityColumn = "ruleId")
    val actions: List<RuleActionEntity>
)

data class AccountWithIdentities(
    @Embedded val account: AccountEntity,
    @Relation(parentColumn = "id", entityColumn = "accountId")
    val identities: List<IdentityEntity>
)

/** List-screen projection: everything the row needs, none of the body columns. */
data class MessageSummary(
    val id: Long,
    val accountId: Long,
    val folderId: Long,
    val uid: Long,
    val subject: String,
    val fromName: String?,
    val fromAddress: String?,
    val toList: String,
    val receivedAt: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val answered: Boolean,
    val hasAttachments: Boolean,
    val sizeBytes: Long,
    val preview: String,
    val isLocal: Boolean,
    val bodyDownloaded: Boolean,
    /** Sender appears on an enabled blocklist; the row is drawn in red. */
    val spam: Boolean,
    /** The conversation this belongs to, or null for mail cached before threading. */
    val threadId: String? = null,
    /**
     * How many messages the row stands for. One in a flat list, and the size of
     * the conversation when the list is gathering them.
     */
    val threadCount: Int = 1
)
