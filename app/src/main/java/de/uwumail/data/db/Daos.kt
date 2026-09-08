package de.uwumail.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

private const val SPAM_FLAG =
    """EXISTS(
        SELECT 1 FROM blocklist_entries e
        JOIN blocklists b ON b.id = e.listId
        WHERE b.enabled = 1
          AND (e.pattern = messages.senderDomain OR e.pattern = LOWER(messages.fromAddress))
    ) AS spam"""

/**
 * What a typed query is matched against.
 *
 * Shared by all three searches on purpose: a folder search that quietly covered
 * less than the global one meant the same words found a mail from one screen and
 * not from another, which reads as mail having gone missing.
 */
private const val TEXT_MATCH =
    """(subject LIKE '%' || :q || '%' OR
        fromAddress LIKE '%' || :q || '%' OR
        fromName LIKE '%' || :q || '%' OR
        toList LIKE '%' || :q || '%' OR
        preview LIKE '%' || :q || '%' OR
        bodyPlain LIKE '%' || :q || '%')"""

/**
 * One row per conversation instead of per message.
 *
 * The newest message of each thread stands for it, and carries the size of the
 * conversation and whether any of it is still unread — a thread with one unread
 * reply in it is unread, however long ago the rest was dealt with.
 *
 * `:scope` is the set of folders being gathered over, so a conversation in the
 * inbox does not pull in its own copy out of Sent.
 */
private const val THREAD_KEY = "COALESCE(messages.threadId, 'id:' || messages.id)"

/**
 * Everything a list row needs, bar which conversation it belongs to and
 * whether it has been read.
 *
 * `seen` is left out because a conversation answers it differently from a
 * message: a thread is unread while any part of it is, however long ago the
 * rest was dealt with.
 */
private const val SUMMARY_BASE =
    "id, accountId, folderId, uid, subject, fromName, fromAddress, toList, receivedAt, " +
        "flagged, answered, hasAttachments, sizeBytes, preview, isLocal, bodyDownloaded, " +
        SPAM_FLAG

/**
 * The same, for a list showing one row per message: the row stands for itself,
 * so its conversation is whatever it says and the count is one.
 */
private const val SUMMARY_COLUMNS = "$SUMMARY_BASE, seen, threadId, 1 AS threadCount"

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY position, id")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY position, id")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun get(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id = :id")
    fun observe(id: Long): Flow<AccountEntity?>

    @Transaction
    @Query("SELECT * FROM accounts ORDER BY position, id")
    fun observeWithIdentities(): Flow<List<AccountWithIdentities>>

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int
}

@Dao
interface IdentityDao {
    @Query("SELECT * FROM identities WHERE accountId = :accountId ORDER BY isDefault DESC, id")
    fun observeForAccount(accountId: Long): Flow<List<IdentityEntity>>

    @Query("SELECT * FROM identities ORDER BY accountId, isDefault DESC, id")
    fun observeAll(): Flow<List<IdentityEntity>>

    @Query("SELECT * FROM identities WHERE accountId = :accountId ORDER BY isDefault DESC, id")
    suspend fun forAccount(accountId: Long): List<IdentityEntity>

    @Query("SELECT * FROM identities WHERE id = :id")
    suspend fun get(id: Long): IdentityEntity?

    @Insert
    suspend fun insert(identity: IdentityEntity): Long

    @Update
    suspend fun update(identity: IdentityEntity)

    @Delete
    suspend fun delete(identity: IdentityEntity)

    @Query("UPDATE identities SET isDefault = 0 WHERE accountId = :accountId")
    suspend fun clearDefault(accountId: Long)
}

@Dao
interface FolderDao {
    @Query(
        """SELECT * FROM folders WHERE accountId = :accountId
           ORDER BY COALESCE(sortOverride, 1000 + position), path"""
    )
    fun observeForAccount(accountId: Long): Flow<List<FolderEntity>>

    @Query(
        """SELECT * FROM folders
           ORDER BY accountId, COALESCE(sortOverride, 1000 + position), path"""
    )
    fun observeAll(): Flow<List<FolderEntity>>

    @Query(
        """SELECT * FROM folders WHERE accountId = :accountId
           ORDER BY COALESCE(sortOverride, 1000 + position), path"""
    )
    suspend fun forAccount(accountId: Long): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun get(id: Long): FolderEntity?

    @Query("SELECT * FROM folders WHERE id = :id")
    fun observe(id: Long): Flow<FolderEntity?>

    @Query("SELECT * FROM folders WHERE accountId = :accountId AND path = :path")
    suspend fun getByPath(accountId: Long, path: String): FolderEntity?

    @Query(
        """SELECT * FROM folders
           WHERE accountId = :accountId AND COALESCE(roleOverride, type) = :type LIMIT 1"""
    )
    suspend fun getByType(accountId: Long, type: String): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM folders WHERE accountId = :accountId AND path = :path")
    suspend fun deleteByPath(accountId: Long, path: String)

    @Query(
        """
        UPDATE folders SET
          unreadCount = (
            SELECT COUNT(*) FROM messages
            WHERE folderId = folders.id AND seen = 0 AND pendingRemoval = 0
          ),
          totalCount  = (
            SELECT COUNT(*) FROM messages
            WHERE folderId = folders.id AND pendingRemoval = 0
          )
        WHERE id = :id
        """
    )
    suspend fun refreshCounts(id: Long)

    @Query(
        """
        UPDATE folders SET
          unreadCount = (
            SELECT COUNT(*) FROM messages
            WHERE folderId = folders.id AND seen = 0 AND pendingRemoval = 0
          ),
          totalCount  = (
            SELECT COUNT(*) FROM messages
            WHERE folderId = folders.id AND pendingRemoval = 0
          )
        """
    )
    suspend fun refreshAllCounts()
}

@Dao
interface MessageDao {
    @Query(
        """
        SELECT $SUMMARY_COLUMNS FROM messages
        WHERE folderId = :folderId AND pendingRemoval = 0
        ORDER BY receivedAt DESC LIMIT :limit
        """
    )
    fun observeFolder(folderId: Long, limit: Int): Flow<List<MessageSummary>>

    /**
     * One folder, gathered into conversations.
     *
     * Each row is the newest message of its thread, standing for the whole of
     * it. The counting and the unread flag are taken across the thread, so a
     * conversation with one unread reply reads as unread.
     */
    @Query(
        """
        SELECT $SUMMARY_BASE,
               $THREAD_KEY AS threadId,
               (SELECT MIN(t.seen) FROM messages t
                  WHERE t.folderId = :folderId AND t.pendingRemoval = 0
                    AND COALESCE(t.threadId, 'id:' || t.id) = $THREAD_KEY) AS seen,
               (SELECT COUNT(*) FROM messages t
                  WHERE t.folderId = :folderId AND t.pendingRemoval = 0
                    AND COALESCE(t.threadId, 'id:' || t.id) = $THREAD_KEY) AS threadCount
        FROM messages
        WHERE folderId = :folderId AND pendingRemoval = 0
          AND messages.id = (
              SELECT n.id FROM messages n
               WHERE n.folderId = :folderId AND n.pendingRemoval = 0
                 AND COALESCE(n.threadId, 'id:' || n.id) = $THREAD_KEY
               ORDER BY n.receivedAt DESC, n.id DESC LIMIT 1)
        ORDER BY receivedAt DESC LIMIT :limit
        """
    )
    fun observeFolderThreads(folderId: Long, limit: Int): Flow<List<MessageSummary>>

    /** The same gathering, across every folder playing [type]. */
    @Query(
        """
        SELECT $SUMMARY_BASE,
               $THREAD_KEY AS threadId,
               (SELECT MIN(t.seen) FROM messages t
                  WHERE t.pendingRemoval = 0
                    AND t.folderId IN (SELECT id FROM folders WHERE COALESCE(roleOverride, type) = :type AND hidden = 0)
                    AND COALESCE(t.threadId, 'id:' || t.id) = $THREAD_KEY) AS seen,
               (SELECT COUNT(*) FROM messages t
                  WHERE t.pendingRemoval = 0
                    AND t.folderId IN (SELECT id FROM folders WHERE COALESCE(roleOverride, type) = :type AND hidden = 0)
                    AND COALESCE(t.threadId, 'id:' || t.id) = $THREAD_KEY) AS threadCount
        FROM messages
        WHERE pendingRemoval = 0
          AND folderId IN (SELECT id FROM folders WHERE COALESCE(roleOverride, type) = :type AND hidden = 0)
          AND messages.id = (
              SELECT n.id FROM messages n
               WHERE n.pendingRemoval = 0
                 AND n.folderId IN (SELECT id FROM folders WHERE COALESCE(roleOverride, type) = :type AND hidden = 0)
                 AND COALESCE(n.threadId, 'id:' || n.id) = $THREAD_KEY
               ORDER BY n.receivedAt DESC, n.id DESC LIMIT 1)
        ORDER BY receivedAt DESC LIMIT :limit
        """
    )
    fun observeUnifiedThreads(type: String, limit: Int): Flow<List<MessageSummary>>

    /** Every message of one conversation, oldest first, the way it was read. */
    @Query(
        """
        SELECT $SUMMARY_BASE, seen, $THREAD_KEY AS threadId, 1 AS threadCount
        FROM messages
        WHERE pendingRemoval = 0 AND $THREAD_KEY = :threadId
          AND folderId IN (SELECT id FROM folders WHERE hidden = 0)
        ORDER BY receivedAt ASC LIMIT :limit
        """
    )
    fun observeThread(threadId: String, limit: Int): Flow<List<MessageSummary>>

    /** Every message across all accounts whose folder plays [type], newest first. */
    @Query(
        """
        SELECT $SUMMARY_COLUMNS FROM messages
        WHERE pendingRemoval = 0
          AND folderId IN (SELECT id FROM folders WHERE COALESCE(roleOverride, type) = :type AND hidden = 0)
        ORDER BY receivedAt DESC LIMIT :limit
        """
    )
    fun observeUnified(type: String, limit: Int): Flow<List<MessageSummary>>

    @Query(
        """
        SELECT $SUMMARY_COLUMNS FROM messages
        WHERE folderId = :folderId AND pendingRemoval = 0 AND $TEXT_MATCH
        ORDER BY receivedAt DESC LIMIT :limit
        """
    )
    fun searchInFolder(folderId: Long, q: String, limit: Int): Flow<List<MessageSummary>>

    /**
     * The same search, restricted to the folders playing [type] — what the
     * unified views need. Without it a query typed into "All inboxes" would
     * have nowhere to go and the list would sit there unfiltered.
     */
    @Query(
        """
        SELECT $SUMMARY_COLUMNS FROM messages
        WHERE pendingRemoval = 0
          AND folderId IN (SELECT id FROM folders WHERE COALESCE(roleOverride, type) = :type AND hidden = 0)
          AND $TEXT_MATCH
        ORDER BY receivedAt DESC LIMIT :limit
        """
    )
    fun searchUnified(type: String, q: String, limit: Int): Flow<List<MessageSummary>>

    /** The same search across every folder of every account. */
    @Query(
        """
        SELECT $SUMMARY_COLUMNS FROM messages
        WHERE pendingRemoval = 0
          AND folderId IN (SELECT id FROM folders WHERE hidden = 0)
          AND $TEXT_MATCH
        ORDER BY receivedAt DESC LIMIT :limit
        """
    )
    fun searchEverywhere(q: String, limit: Int): Flow<List<MessageSummary>>

    @Query("SELECT * FROM messages WHERE id = :id")
    fun observeFull(id: Long): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE id IN (:ids)")
    suspend fun getAll(ids: List<Long>): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE folderId = :folderId AND uid = :uid")
    suspend fun getByUid(folderId: Long, uid: Long): MessageEntity?

    @Query(
        """SELECT id FROM messages
           WHERE folderId = :folderId AND seen = 0 AND pendingRemoval = 0"""
    )
    suspend fun unreadIdsIn(folderId: Long): List<Long>

    /** Everything a folder holds, for emptying it. */
    @Query("SELECT id FROM messages WHERE folderId = :folderId AND pendingRemoval = 0")
    suspend fun idsIn(folderId: Long): List<Long>

    /** Unread mail whose body is not cached yet, newest first — what preloading works through. */
    @Query(
        """SELECT id FROM messages
           WHERE folderId = :folderId AND seen = 0 AND bodyDownloaded = 0
             AND isLocal = 0 AND pendingRemoval = 0
           ORDER BY receivedAt DESC LIMIT :limit"""
    )
    suspend fun unreadWithoutBody(folderId: Long, limit: Int): List<Long>

    @Query(
        """SELECT id FROM messages
           WHERE seen = 0 AND bodyDownloaded = 0 AND isLocal = 0 AND pendingRemoval = 0
             AND folderId IN (SELECT id FROM folders WHERE COALESCE(roleOverride, type) = :type AND hidden = 0)
           ORDER BY receivedAt DESC LIMIT :limit"""
    )
    suspend fun unreadWithoutBodyUnified(type: String, limit: Int): List<Long>

    @Query("SELECT uid FROM messages WHERE folderId = :folderId")
    suspend fun uidsIn(folderId: Long): List<Long>

    @Query("SELECT MAX(uid) FROM messages WHERE folderId = :folderId")
    suspend fun maxUid(folderId: Long): Long?

    @Query("SELECT * FROM messages WHERE accountId = :accountId AND notified = 0 AND seen = 0")
    suspend fun pendingNotifications(accountId: Long): List<MessageEntity>

    /**
     * Notifications currently standing in the shade for an account: posted,
     * still unread, and not on their way out. What decides whether a group
     * summary belongs there — which cannot be answered from one sync's worth of
     * new mail, since push delivers it a message at a time.
     */
    @Query(
        """SELECT COUNT(*) FROM messages
           WHERE accountId = :accountId AND notified = 1 AND seen = 0 AND pendingRemoval = 0"""
    )
    suspend fun standingNotifications(accountId: Long): Int

    /**
     * Corpus used to score candidate rules in the rule wizard.
     *
     * The bodies are left behind on purpose. The suggester looks at senders,
     * headers, subjects and recipients and never at body text, while reading
     * the bodies of a few thousand messages means holding all of them in
     * memory at once — and building a match context from an HTML body runs it
     * through an HTML parser, once per message. The preview is kept, being a
     * short line that is already there.
     */
    @Query(
        """
        SELECT id, accountId, folderId, uid, messageIdHeader, threadId, subject, fromName, fromAddress,
               senderDomain, toList, ccList, bccList, replyTo, sentAt, receivedAt, seen,
               flagged, answered, draft, hasAttachments, sizeBytes, preview,
               NULL AS bodyPlain, NULL AS bodyHtml, bodyDownloaded, headersJson,
               rawFilePath, isLocal, notified, rulesApplied, pendingRemoval
        FROM messages ORDER BY receivedAt DESC LIMIT :limit
        """
    )
    suspend fun recentForAnalysis(limit: Int): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>): List<Long>

    @Update
    suspend fun update(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<Long>)

    /** The rows behind a set of uids, so what is about to be forgotten can be found. */
    @Query("SELECT id FROM messages WHERE folderId = :folderId AND uid IN (:uids)")
    suspend fun idsForUids(folderId: Long, uids: List<Long>): List<Long>

    @Query("DELETE FROM messages WHERE folderId = :folderId AND uid IN (:uids)")
    suspend fun deleteUids(folderId: Long, uids: List<Long>)

    @Query("DELETE FROM messages WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: Long)

    @Query("UPDATE messages SET seen = :seen WHERE id IN (:ids)")
    suspend fun setSeen(ids: List<Long>, seen: Boolean)

    @Query("UPDATE messages SET flagged = :flagged WHERE id IN (:ids)")
    suspend fun setFlagged(ids: List<Long>, flagged: Boolean)

    @Query("UPDATE messages SET answered = :answered WHERE id IN (:ids)")
    suspend fun setAnswered(ids: List<Long>, answered: Boolean)

    @Query("UPDATE messages SET notified = 1 WHERE id IN (:ids)")
    suspend fun markNotified(ids: List<Long>)

    /** No longer standing in the shade, so no longer counted by the line above it. */
    @Query("UPDATE messages SET notified = 0 WHERE id IN (:ids)")
    suspend fun clearNotified(ids: List<Long>)

    /** Hides or restores rows while a server-side removal is in flight. */
    @Query("UPDATE messages SET pendingRemoval = :pending WHERE id IN (:ids)")
    suspend fun setPendingRemoval(ids: List<Long>, pending: Boolean)

    /** Shows mail again by uid, for a folder-sync relocation the server refused. */
    @Query("UPDATE messages SET pendingRemoval = 0 WHERE folderId = :folderId AND uid IN (:uids)")
    suspend fun clearPendingRemoval(folderId: Long, uids: List<Long>)

    /** Un-hides everything left hidden by a removal that never finished. */
    @Query("UPDATE messages SET pendingRemoval = 0 WHERE pendingRemoval = 1")
    suspend fun clearPendingRemovals()

    @Query("UPDATE messages SET folderId = :folderId, uid = :uid, isLocal = :isLocal WHERE id = :id")
    suspend fun reassign(id: Long, folderId: Long, uid: Long, isLocal: Boolean)

    @Query("SELECT MIN(uid) FROM messages WHERE folderId = :folderId")
    suspend fun minUid(folderId: Long): Long?

    /** Mail cached before threading existed, for filling its conversation in. */
    @Query("SELECT * FROM messages WHERE threadId IS NULL LIMIT :limit")
    suspend fun withoutThread(limit: Int): List<MessageEntity>

    @Query("UPDATE messages SET threadId = :threadId WHERE id = :id")
    suspend fun setThreadId(id: Long, threadId: String)
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE messageId = :messageId ORDER BY id")
    fun observeFor(messageId: Long): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE messageId = :messageId ORDER BY id")
    suspend fun forMessage(messageId: Long): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun get(id: Long): AttachmentEntity?

    @Insert
    suspend fun insertAll(items: List<AttachmentEntity>)

    /**
     * Every stored attachment name, for scoring a rule against cached mail.
     * Read in one go and grouped in memory: the corpus is thousands of
     * messages and a query each would be thousands of queries.
     */
    @Query("SELECT messageId, fileName FROM attachments WHERE isInline = 0 AND fileName != ''")
    suspend fun fileNames(): List<AttachmentName>

    @Query("DELETE FROM attachments WHERE messageId = :messageId")
    suspend fun clearFor(messageId: Long)

    @Update
    suspend fun update(item: AttachmentEntity)
}

/** One attachment's name against the message carrying it. */
data class AttachmentName(val messageId: Long, val fileName: String)

@Dao
interface RuleDao {
    @Transaction
    @Query("SELECT * FROM rules ORDER BY priority, id")
    fun observeAll(): Flow<List<RuleWithDetails>>

    @Transaction
    @Query("SELECT * FROM rules WHERE enabled = 1 ORDER BY priority, id")
    suspend fun enabledRules(): List<RuleWithDetails>

    /** Every rule, enabled or not — a backup that dropped the disabled ones would lie. */
    @Transaction
    @Query("SELECT * FROM rules ORDER BY priority, id")
    suspend fun enabledRulesForBackup(): List<RuleWithDetails>

    @Transaction
    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun get(id: Long): RuleWithDetails?

    @Transaction
    @Query("SELECT * FROM rules WHERE id = :id")
    fun observe(id: Long): Flow<RuleWithDetails?>

    @Insert
    suspend fun insertRule(rule: RuleEntity): Long

    @Update
    suspend fun updateRule(rule: RuleEntity)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun deleteRule(id: Long)

    @Insert
    suspend fun insertConditions(items: List<RuleConditionEntity>)

    @Query("DELETE FROM rule_conditions WHERE ruleId = :ruleId")
    suspend fun clearConditions(ruleId: Long)

    @Insert
    suspend fun insertActions(items: List<RuleActionEntity>)

    @Query("DELETE FROM rule_actions WHERE ruleId = :ruleId")
    suspend fun clearActions(ruleId: Long)

    /**
     * Writes the order rules run in.
     *
     * Renumbered from the top rather than swapping a pair: the priorities that
     * arrive here are whatever previous versions and hand-edited rules left
     * behind, and two rules sharing one number have no defined order at all.
     */
    @Transaction
    suspend fun renumber(ordered: List<RuleEntity>) {
        ordered.forEachIndexed { index, rule ->
            if (rule.priority != index) updateRule(rule.copy(priority = index))
        }
    }

    @Query("UPDATE rules SET matchCount = matchCount + 1, lastMatchedAt = :at WHERE id = :id")
    suspend fun recordMatch(id: Long, at: Long)

    @Insert
    suspend fun log(entry: RuleLogEntity)

    @Query("SELECT * FROM rule_log ORDER BY at DESC LIMIT :limit")
    fun observeLog(limit: Int): Flow<List<RuleLogEntity>>

    @Query("DELETE FROM rule_log WHERE at < :before")
    suspend fun pruneLog(before: Long)

    /**
     * Copies a rule whole, disabled.
     *
     * Off to begin with because the commonest reason to copy one is to change
     * it into something else, and two identical rules both running is not what
     * anybody meant by "duplicate".
     */
    @Transaction
    suspend fun duplicate(entry: RuleWithDetails, suffix: String): Long {
        val id = insertRule(
            entry.rule.copy(
                id = 0,
                name = "${entry.rule.name} $suffix",
                enabled = false,
                matchCount = 0,
                lastMatchedAt = null,
                createdAt = System.currentTimeMillis()
            )
        )
        insertConditions(entry.conditions.map { it.copy(id = 0, ruleId = id) })
        insertActions(entry.actions.map { it.copy(id = 0, ruleId = id) })
        return id
    }

    @Transaction
    suspend fun replaceRule(
        rule: RuleEntity,
        conditions: List<RuleConditionEntity>,
        actions: List<RuleActionEntity>
    ): Long {
        val id = if (rule.id == 0L) insertRule(rule) else {
            updateRule(rule); rule.id
        }
        clearConditions(id)
        clearActions(id)
        insertConditions(conditions.map { it.copy(id = 0, ruleId = id) })
        insertActions(actions.mapIndexed { i, a -> a.copy(id = 0, ruleId = id, orderIndex = i) })
        return id
    }
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY createdAt")
    fun observeAll(): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox ORDER BY createdAt")
    suspend fun pending(): List<OutboxEntity>

    @Insert
    suspend fun insert(item: OutboxEntity): Long

    @Update
    suspend fun update(item: OutboxEntity)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>
}


@Dao
interface BlocklistDao {
    @Query("SELECT * FROM blocklists ORDER BY position, id")
    fun observeAll(): Flow<List<BlocklistEntity>>

    @Query("SELECT * FROM blocklists ORDER BY position, id")
    suspend fun all(): List<BlocklistEntity>

    @Query("SELECT * FROM blocklists WHERE id = :id")
    suspend fun get(id: Long): BlocklistEntity?

    @Query("SELECT * FROM blocklists WHERE url IS NULL LIMIT 1")
    suspend fun manualList(): BlocklistEntity?

    @Insert
    suspend fun insert(list: BlocklistEntity): Long

    @Update
    suspend fun update(list: BlocklistEntity)

    @Query("DELETE FROM blocklists WHERE id = :id AND builtIn = 0")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM blocklists")
    suspend fun count(): Int

    @Query("SELECT pattern FROM blocklist_entries WHERE listId = :listId ORDER BY pattern")
    fun observeEntries(listId: Long): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntries(entries: List<BlocklistEntryEntity>)

    @Query("DELETE FROM blocklist_entries WHERE listId = :listId")
    suspend fun clearEntries(listId: Long)

    @Query("DELETE FROM blocklist_entries WHERE listId = :listId AND pattern = :pattern")
    suspend fun deleteEntry(listId: Long, pattern: String)

    @Query("SELECT COUNT(*) FROM blocklist_entries WHERE listId = :listId")
    suspend fun entryCount(listId: Long): Int

    /** True when any enabled list covers this sender. */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM blocklist_entries e
            JOIN blocklists b ON b.id = e.listId
            WHERE b.enabled = 1 AND (e.pattern = :domain OR e.pattern = :address)
        )
        """
    )
    suspend fun isBlocked(domain: String?, address: String?): Boolean

    @Transaction
    suspend fun replaceEntries(listId: Long, patterns: List<String>) {
        clearEntries(listId)
        patterns.chunked(500).forEach { chunk ->
            insertEntries(chunk.map { BlocklistEntryEntity(listId = listId, pattern = it) })
        }
    }
}
