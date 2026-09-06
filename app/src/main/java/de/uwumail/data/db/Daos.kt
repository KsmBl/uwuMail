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

private const val SUMMARY_COLUMNS =
    "id, accountId, folderId, uid, subject, fromName, fromAddress, toList, receivedAt, " +
        "seen, flagged, answered, hasAttachments, sizeBytes, preview, isLocal, bodyDownloaded, " +
        SPAM_FLAG

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

    @Query("SELECT * FROM folders WHERE accountId = :accountId AND type = :type LIMIT 1")
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

    /** Every message across all accounts whose folder plays [type], newest first. */
    @Query(
        """
        SELECT $SUMMARY_COLUMNS FROM messages
        WHERE pendingRemoval = 0
          AND folderId IN (SELECT id FROM folders WHERE type = :type AND hidden = 0)
        ORDER BY receivedAt DESC LIMIT :limit
        """
    )
    fun observeUnified(type: String, limit: Int): Flow<List<MessageSummary>>

    @Query(
        """
        SELECT $SUMMARY_COLUMNS FROM messages
        WHERE folderId = :folderId AND pendingRemoval = 0 AND (
            subject LIKE '%' || :q || '%' OR
            fromAddress LIKE '%' || :q || '%' OR
            fromName LIKE '%' || :q || '%' OR
            preview LIKE '%' || :q || '%')
        ORDER BY receivedAt DESC LIMIT :limit
        """
    )
    fun searchInFolder(folderId: Long, q: String, limit: Int): Flow<List<MessageSummary>>

    /** The same search across every folder of every account. */
    @Query(
        """
        SELECT $SUMMARY_COLUMNS FROM messages
        WHERE pendingRemoval = 0
          AND folderId IN (SELECT id FROM folders WHERE hidden = 0)
          AND (
            subject LIKE '%' || :q || '%' OR
            fromAddress LIKE '%' || :q || '%' OR
            fromName LIKE '%' || :q || '%' OR
            toList LIKE '%' || :q || '%' OR
            preview LIKE '%' || :q || '%' OR
            bodyPlain LIKE '%' || :q || '%')
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
             AND folderId IN (SELECT id FROM folders WHERE type = :type AND hidden = 0)
           ORDER BY receivedAt DESC LIMIT :limit"""
    )
    suspend fun unreadWithoutBodyUnified(type: String, limit: Int): List<Long>

    @Query("SELECT uid FROM messages WHERE folderId = :folderId")
    suspend fun uidsIn(folderId: Long): List<Long>

    @Query("SELECT MAX(uid) FROM messages WHERE folderId = :folderId")
    suspend fun maxUid(folderId: Long): Long?

    @Query("SELECT * FROM messages WHERE accountId = :accountId AND notified = 0 AND seen = 0")
    suspend fun pendingNotifications(accountId: Long): List<MessageEntity>

    /** Corpus used to score candidate rules in the rule wizard. */
    @Query("SELECT * FROM messages ORDER BY receivedAt DESC LIMIT :limit")
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

    @Query("DELETE FROM messages WHERE folderId = :folderId AND uid IN (:uids)")
    suspend fun deleteUids(folderId: Long, uids: List<Long>)

    @Query("DELETE FROM messages WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: Long)

    @Query("UPDATE messages SET seen = :seen WHERE id IN (:ids)")
    suspend fun setSeen(ids: List<Long>, seen: Boolean)

    @Query("UPDATE messages SET flagged = :flagged WHERE id IN (:ids)")
    suspend fun setFlagged(ids: List<Long>, flagged: Boolean)

    @Query("UPDATE messages SET notified = 1 WHERE id IN (:ids)")
    suspend fun markNotified(ids: List<Long>)

    /** Hides or restores rows while a server-side removal is in flight. */
    @Query("UPDATE messages SET pendingRemoval = :pending WHERE id IN (:ids)")
    suspend fun setPendingRemoval(ids: List<Long>, pending: Boolean)

    /** Un-hides everything left hidden by a removal that never finished. */
    @Query("UPDATE messages SET pendingRemoval = 0 WHERE pendingRemoval = 1")
    suspend fun clearPendingRemovals()

    @Query("UPDATE messages SET folderId = :folderId, uid = :uid, isLocal = :isLocal WHERE id = :id")
    suspend fun reassign(id: Long, folderId: Long, uid: Long, isLocal: Boolean)

    @Query("SELECT MIN(uid) FROM messages WHERE folderId = :folderId")
    suspend fun minUid(folderId: Long): Long?
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

    @Query("DELETE FROM attachments WHERE messageId = :messageId")
    suspend fun clearFor(messageId: Long)

    @Update
    suspend fun update(item: AttachmentEntity)
}

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

    @Query("UPDATE rules SET matchCount = matchCount + 1, lastMatchedAt = :at WHERE id = :id")
    suspend fun recordMatch(id: Long, at: Long)

    @Insert
    suspend fun log(entry: RuleLogEntity)

    @Query("SELECT * FROM rule_log ORDER BY at DESC LIMIT :limit")
    fun observeLog(limit: Int): Flow<List<RuleLogEntity>>

    @Query("DELETE FROM rule_log WHERE at < :before")
    suspend fun pruneLog(before: Long)

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
