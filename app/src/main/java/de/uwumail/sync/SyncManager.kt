package de.uwumail.sync

import android.content.Context
import de.uwumail.core.ActionType
import de.uwumail.core.FolderType
import de.uwumail.core.DeviceDownloads
import de.uwumail.core.Json
import de.uwumail.core.SavedAttachments
import de.uwumail.core.joinAddresses
import de.uwumail.data.crypto.CredentialStore
import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.AppDatabase
import de.uwumail.data.db.AttachmentEntity
import de.uwumail.data.db.FolderEntity
import de.uwumail.data.db.MessageEntity
import de.uwumail.data.db.OutboxEntity
import de.uwumail.data.db.RuleLogEntity
import de.uwumail.mail.FetchedMessage
import de.uwumail.mail.FolderClassifier
import de.uwumail.mail.ImapPool
import de.uwumail.mail.MailException
import de.uwumail.mail.MimeUtil
import de.uwumail.mail.SmtpSender
import de.uwumail.mail.oauth.TokenStore
import de.uwumail.notify.NotificationPriority
import de.uwumail.notify.Notifier
import de.uwumail.rules.MatchContext
import de.uwumail.rules.RuleEngine
import de.uwumail.rules.RulePlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.mail.Flags

/** What kind of removal is waiting to be undone; the wording is the UI's business. */
enum class UndoKind { ARCHIVE, TRASH, DELETE, MOVE }

/** A removal the user has a moment to take back. */
data class Undoable(val token: Long, val kind: UndoKind, val count: Int)

data class SyncState(
    val running: Set<Long> = emptySet(),
    val lastError: String? = null,
    val lastCompletedAt: Long = 0
)

/**
 * Owns every side effect that touches a server.
 *
 * The UI and the workers both go through here, so IMAP access stays serialised
 * per account ([ImapPool]) and the local database is only ever updated after the
 * server has acknowledged the change.
 */
class SyncManager(
    private val context: Context,
    private val db: AppDatabase,
    private val pool: ImapPool,
    private val credentials: CredentialStore,
    private val tokenStore: TokenStore,
    private val ruleEngine: RuleEngine,
    private val notifier: Notifier,
    private val smtp: SmtpSender
) {

    private val _state = MutableStateFlow(SyncState())
    val state = _state.asStateFlow()

    /**
     * User-facing failures from work that outlived the screen that started it —
     * an optimistic delete the server later refused, say.
     */
    private val _alerts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val alerts = _alerts.asSharedFlow()

    /** Removals that have been started but can still be taken back. */
    private val _undoable = MutableSharedFlow<Undoable>(extraBufferCapacity = 8)
    val undoable = _undoable.asSharedFlow()

    /**
     * Deferred removals outlive the screen that asked for them, so they cannot
     * run in a view model's scope: leaving the list would cancel the delete and
     * silently leave the mail where it was.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val waiting = ConcurrentHashMap<Long, Waiting>()
    private val tokens = AtomicLong()

    private class Waiting(val job: Job, val messageIds: List<Long>)

    // ------------------------------------------------------------------ sync

    suspend fun syncAll() {
        db.accountDao().getAll().filter { it.syncEnabled }.forEach { account ->
            runCatching { syncAccount(account.id) }
                .onFailure { error -> _state.update { it.copy(lastError = error.message) } }
        }
        sendOutbox()
    }

    suspend fun syncAccount(accountId: Long) {
        _state.update { it.copy(running = it.running + accountId, lastError = null) }
        try {
            refreshFolders(accountId)
            val folders = db.folderDao().forAccount(accountId)
                .filter { !it.isLocal && it.syncEnabled && it.selectable }
            // Inbox first so new mail shows up before the long tail of folders.
            folders.sortedBy { if (it.type == FolderType.INBOX.name) 0 else 1 }
                .forEach { folder -> runCatching { syncFolder(folder) } }
            db.folderDao().refreshAllCounts()
            _state.update { it.copy(lastCompletedAt = System.currentTimeMillis()) }
        } catch (e: Throwable) {
            _state.update { it.copy(lastError = e.message ?: e.toString()) }
            throw e
        } finally {
            _state.update { it.copy(running = it.running - accountId) }
        }
    }

    /** Reconciles the local folder list with what the server reports. */
    suspend fun refreshFolders(accountId: Long) {
        val remote = pool.use(accountId) { it.listFolders() }
        val account = db.accountDao().get(accountId) ?: return
        val existing = db.folderDao().forAccount(accountId).associateBy { it.path }

        // The inbox always sits at the top, then the other well-known folders,
        // then everything else alphabetically.
        val ordered = FolderClassifier.order(remote, { it.type }, { it.path })

        ordered.forEachIndexed { index, folder ->
            val current = existing[folder.path]
            if (current == null) {
                db.folderDao().insert(
                    FolderEntity(
                        accountId = accountId,
                        path = folder.path,
                        displayName = folder.displayName,
                        delimiter = folder.delimiter,
                        type = folder.type,
                        isLocal = false,
                        selectable = folder.selectable,
                        subscribed = folder.subscribed,
                        // Only the inbox syncs automatically; the rest sync on open.
                        syncEnabled = folder.type == FolderType.INBOX.name,
                        position = index
                    )
                )
            } else if (current.type != folder.type ||
                current.selectable != folder.selectable ||
                current.position != index
            ) {
                db.folderDao().update(
                    current.copy(
                        type = folder.type,
                        selectable = folder.selectable,
                        position = index
                    )
                )
            }
        }

        val remotePaths = remote.mapTo(HashSet()) { it.path }
        existing.values.filter { !it.isLocal && it.path !in remotePaths }
            .forEach { db.folderDao().delete(it.id) }

        // Fill in special-folder routing the first time we see the server's layout.
        if (account.archiveFolder == null || account.trashFolder == null ||
            account.sentFolder == null || account.draftsFolder == null
        ) {
            db.accountDao().update(
                account.copy(
                    archiveFolder = account.archiveFolder ?: remote.firstOrNull { it.type == FolderType.ARCHIVE.name }?.path,
                    trashFolder = account.trashFolder ?: remote.firstOrNull { it.type == FolderType.TRASH.name }?.path,
                    sentFolder = account.sentFolder ?: remote.firstOrNull { it.type == FolderType.SENT.name }?.path,
                    draftsFolder = account.draftsFolder ?: remote.firstOrNull { it.type == FolderType.DRAFTS.name }?.path
                )
            )
        }
    }

    /**
     * Syncs every folder playing [type] across all accounts — what the unified
     * views need.
     *
     * Folder-level `syncEnabled` is deliberately ignored: it governs unattended
     * background sync, and a pull-to-refresh is an explicit request for these
     * folders right now. Sent and Trash are not background-synced by default, so
     * honouring the flag here would leave those views permanently empty.
     */
    suspend fun syncUnified(type: FolderType) {
        val accounts = db.accountDao().getAll()
        accounts.forEach { account ->
            runCatching {
                refreshFolders(account.id)
                db.folderDao().forAccount(account.id)
                    .filter { !it.isLocal && it.selectable && !it.hidden && it.type == type.name }
                    .forEach { folder -> runCatching { syncFolder(folder) } }
            }.onFailure { error -> _state.update { it.copy(lastError = error.message) } }
        }
        db.folderDao().refreshAllCounts()
        runCatching { sendOutbox() }
    }

    suspend fun syncFolder(folderId: Long) {
        val folder = db.folderDao().get(folderId) ?: return
        if (folder.isLocal) return
        syncFolder(folder)
    }

    private suspend fun syncFolder(folder: FolderEntity) {
        val account = db.accountDao().get(folder.accountId) ?: return
        val status = pool.use(account.id) { it.status(folder.path) }

        var tracked = folder
        if (tracked.uidValidity != 0L && tracked.uidValidity != status.uidValidity) {
            // The server renumbered the folder; everything we cached is stale.
            db.messageDao().clearFolder(tracked.id)
            tracked = tracked.copy(highestUid = 0)
        }
        if (tracked.uidValidity != status.uidValidity) {
            tracked = tracked.copy(uidValidity = status.uidValidity)
        }

        val firstRun = tracked.highestUid == 0L
        val limit = if (firstRun) INITIAL_FETCH else INCREMENTAL_FETCH
        val fetched = pool.use(account.id) { it.fetchNewer(tracked.path, tracked.highestUid, limit) }

        if (fetched.isNotEmpty()) {
            processNewMessages(account, tracked, fetched, notify = !firstRun)
            tracked = tracked.copy(highestUid = fetched.maxOf { it.uid })
        }

        syncFlags(account, tracked)
        pruneVanished(account, tracked)

        db.folderDao().update(tracked)
        db.folderDao().refreshCounts(tracked.id)
    }

    /** Pages one screenful of older mail into the cache. Returns how many arrived. */
    suspend fun loadOlder(folderId: Long): Int {
        val folder = db.folderDao().get(folderId) ?: return 0
        if (folder.isLocal) return 0
        val account = db.accountDao().get(folder.accountId) ?: return 0
        val oldest = db.messageDao().minUid(folderId) ?: return 0
        val fetched = pool.use(account.id) { it.fetchOlder(folder.path, oldest, PAGE_SIZE) }
        if (fetched.isEmpty()) return 0
        // Older mail is history, not news: no rules, no notifications.
        db.messageDao().insertAll(fetched.map { it.toEntity(account.id, folder.id) })
        db.folderDao().refreshCounts(folderId)
        return fetched.size
    }

    private suspend fun processNewMessages(
        account: AccountEntity,
        folder: FolderEntity,
        fetched: List<FetchedMessage>,
        notify: Boolean
    ) {
        val rules = db.ruleDao().enabledRules()
        val needsBody = rules.any { entry ->
            entry.conditions.any { it.field == de.uwumail.core.RuleField.BODY.name }
        }

        val archivePath = account.archiveFolder
        val trashPath = account.trashFolder

        val moves = mutableMapOf<String, MutableList<Long>>()
        val copies = mutableMapOf<String, MutableList<Long>>()
        val permanentDeletes = mutableListOf<Long>()
        val markSeen = mutableListOf<Long>()
        val markUnseen = mutableListOf<Long>()
        val setFlagged = mutableListOf<Long>()
        val clearFlagged = mutableListOf<Long>()
        val toLocal = mutableMapOf<String, MutableList<Long>>()
        val copyToLocal = mutableMapOf<String, MutableList<Long>>()
        val relocatedUids = mutableSetOf<Long>()
        val plans = mutableMapOf<Long, RulePlan>()

        for (message in fetched) {
            var entity = message.toEntity(account.id, folder.id)

            if (needsBody) {
                runCatching { pool.use(account.id) { it.fetchBody(folder.path, message.uid) } }
                    .getOrNull()?.let { body ->
                        entity = entity.copy(
                            bodyPlain = body.plain,
                            bodyHtml = body.html,
                            bodyDownloaded = true,
                            preview = MimeUtil.preview(body.plain, body.html)
                        )
                    }
            }

            val ctx = MatchContext.of(entity, folder.path)
            val plan = ruleEngine.plan(ctx, rules)
            plans[message.uid] = plan

            if (plan.markRead) { markSeen += message.uid; entity = entity.copy(seen = true) }
            if (plan.markUnread) { markUnseen += message.uid; entity = entity.copy(seen = false) }
            when (plan.flag) {
                true -> { setFlagged += message.uid; entity = entity.copy(flagged = true) }
                false -> { clearFlagged += message.uid; entity = entity.copy(flagged = false) }
                null -> Unit
            }

            plan.copies.forEach { action ->
                val target = action.arg ?: return@forEach
                when (action.type) {
                    ActionType.COPY_TO_FOLDER -> copies.getOrPut(target) { mutableListOf() } += message.uid
                    ActionType.COPY_TO_LOCAL -> copyToLocal.getOrPut(target) { mutableListOf() } += message.uid
                    else -> Unit
                }
            }

            plan.relocation?.let { action ->
                val target = when (action.type) {
                    ActionType.ARCHIVE -> archivePath
                    ActionType.MOVE_TO_TRASH -> trashPath
                    ActionType.MOVE_TO_FOLDER -> action.arg
                    ActionType.MOVE_TO_LOCAL -> null
                    ActionType.DELETE_PERMANENTLY -> null
                    else -> null
                }
                when {
                    action.type == ActionType.DELETE_PERMANENTLY -> {
                        permanentDeletes += message.uid
                        relocatedUids += message.uid
                    }
                    action.type == ActionType.MOVE_TO_LOCAL && action.arg != null -> {
                        toLocal.getOrPut(action.arg) { mutableListOf() } += message.uid
                    }
                    target != null -> {
                        moves.getOrPut(target) { mutableListOf() } += message.uid
                        relocatedUids += message.uid
                    }
                }
            }

            if (plan.download && !entity.bodyDownloaded) {
                runCatching { pool.use(account.id) { it.fetchBody(folder.path, message.uid) } }
                    .getOrNull()?.let { body ->
                        entity = entity.copy(
                            bodyPlain = body.plain,
                            bodyHtml = body.html,
                            bodyDownloaded = true,
                            preview = MimeUtil.preview(body.plain, body.html)
                        )
                    }
            }

            db.messageDao().insert(entity.copy(rulesApplied = true))

            if (plan.matched) {
                val at = System.currentTimeMillis()
                plan.matchedRules.forEach { rule ->
                    db.ruleDao().recordMatch(rule.id, at)
                    db.ruleDao().log(
                        RuleLogEntity(
                            ruleId = rule.id,
                            ruleName = rule.name,
                            accountId = account.id,
                            subject = entity.subject,
                            fromAddress = entity.fromAddress,
                            actionsTaken = plan.summary(),
                            at = at
                        )
                    )
                }
            }
        }

        // Server-side effects, batched per target so one IMAP round trip covers many mails.
        runCatching {
            pool.use(account.id) { client ->
                if (markSeen.isNotEmpty()) client.setFlags(folder.path, markSeen, Flags.Flag.SEEN, true)
                if (markUnseen.isNotEmpty()) client.setFlags(folder.path, markUnseen, Flags.Flag.SEEN, false)
                if (setFlagged.isNotEmpty()) client.setFlags(folder.path, setFlagged, Flags.Flag.FLAGGED, true)
                if (clearFlagged.isNotEmpty()) client.setFlags(folder.path, clearFlagged, Flags.Flag.FLAGGED, false)
                copies.forEach { (target, uids) -> runCatching { client.copyMessages(folder.path, uids, target) } }
                moves.forEach { (target, uids) -> runCatching { client.moveMessages(folder.path, uids, target) } }
                if (permanentDeletes.isNotEmpty()) client.deleteMessages(folder.path, permanentDeletes)
            }
        }

        // Notify before pruning, so every notification carries the real row id and
        // tapping it still opens something.
        if (notify && account.notificationsEnabled) {
            notifyFor(account, folder, fetched, plans)
        }

        // Rows for relocated mail belong to the target folder, which will pick them
        // up on its next sync; drop the copies sitting in this folder's cache.
        if (relocatedUids.isNotEmpty()) {
            db.messageDao().deleteUids(folder.id, relocatedUids.toList())
        }

        // Local copies are taken before the moves, so a message that a rule both
        // copies and moves still has a server copy to download here.
        copyToLocal.forEach { (localName, uids) ->
            runCatching { copyUidsToLocal(account, folder, uids, localName) }
        }

        toLocal.forEach { (localName, uids) ->
            runCatching { moveUidsToLocal(account, folder, uids, localName) }
        }
    }

    private suspend fun notifyFor(
        account: AccountEntity,
        folder: FolderEntity,
        fetched: List<FetchedMessage>,
        plans: Map<Long, RulePlan>
    ) {
        if (folder.type != FolderType.INBOX.name) return
        var posted = 0
        for (message in fetched) {
            if (message.seen) continue
            val plan = plans[message.uid] ?: RulePlan.EMPTY
            if (plan.suppressNotification) continue
            // Trashed or deleted mail should not announce itself.
            val relocation = plan.relocation?.type
            if (relocation == ActionType.MOVE_TO_TRASH || relocation == ActionType.DELETE_PERMANENTLY) continue
            if (plan.markRead) continue

            val entity = db.messageDao().getByUid(folder.id, message.uid) ?: continue
            val priority = when {
                plan.notifyHigh -> NotificationPriority.HIGH
                plan.notifySilently -> NotificationPriority.SILENT
                else -> NotificationPriority.DEFAULT
            }
            notifier.notifyNewMail(account, entity, priority)
            db.messageDao().markNotified(listOf(entity.id))
            posted++
        }
        if (posted > 1) notifier.postSummary(account, posted)
    }

    private suspend fun syncFlags(account: AccountEntity, folder: FolderEntity) {
        val uids = db.messageDao().uidsIn(folder.id).sortedDescending().take(FLAG_WINDOW)
        if (uids.isEmpty()) return
        val flags = runCatching { pool.use(account.id) { it.fetchFlags(folder.path, uids) } }
            .getOrNull() ?: return
        flags.forEach { (uid, state) ->
            val local = db.messageDao().getByUid(folder.id, uid) ?: return@forEach
            if (local.seen != state.seen || local.flagged != state.flagged ||
                local.answered != state.answered
            ) {
                db.messageDao().update(
                    local.copy(seen = state.seen, flagged = state.flagged, answered = state.answered)
                )
            }
        }
    }

    private suspend fun pruneVanished(account: AccountEntity, folder: FolderEntity) {
        val localUids = db.messageDao().uidsIn(folder.id).sortedDescending().take(FLAG_WINDOW)
        if (localUids.isEmpty()) return
        val since = localUids.min()
        val serverUids = runCatching { pool.use(account.id) { it.listUids(folder.path, since) } }
            .getOrNull() ?: return
        val gone = localUids.filter { it !in serverUids }
        if (gone.isNotEmpty()) db.messageDao().deleteUids(folder.id, gone)
    }

    // --------------------------------------------------------- user actions

    /**
     * Hides [messageIds] straight away and does the server work afterwards.
     *
     * The list is driven by the database, so the rows disappear the moment the
     * flag is written — the user never waits on the round trip. If the server
     * refuses, the rows come back and the failure is announced rather than
     * leaving mail silently missing.
     *
     * When [undo] is given the server work is held back for a few seconds
     * first, so taking it back is a matter of not doing it rather than of
     * undoing it. That is the only honest way to offer it: a permanent
     * deletion cannot be reversed once the server has been told, and an
     * archive can only be approximated by moving the message back.
     */
    private suspend fun optimistically(
        messageIds: List<Long>,
        undo: UndoKind? = null,
        block: suspend () -> Unit
    ) {
        if (messageIds.isEmpty()) return
        db.messageDao().setPendingRemoval(messageIds, true)
        db.folderDao().refreshAllCounts()
        messageIds.forEach { notifier.cancel(it) }

        if (undo == null) {
            attempt(messageIds, block)
            return
        }

        val token = tokens.incrementAndGet()
        val job = scope.launch {
            delay(UNDO_WINDOW_MILLIS)
            waiting.remove(token)
            runCatching { attempt(messageIds, block) }
        }
        waiting[token] = Waiting(job, messageIds)
        _undoable.tryEmit(Undoable(token, undo, messageIds.size))
    }

    private suspend fun attempt(messageIds: List<Long>, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            restore(messageIds)
            _alerts.tryEmit("Could not complete that: ${e.message ?: e.toString()}")
            throw e
        }
    }

    /**
     * Takes back a removal that has not happened yet. Returns false when the
     * moment has passed, which the caller should treat as "too late" rather
     * than as an error.
     */
    suspend fun undo(token: Long): Boolean {
        val entry = waiting.remove(token) ?: return false
        entry.job.cancel()
        restore(entry.messageIds)
        return true
    }

    private suspend fun restore(messageIds: List<Long>) {
        db.messageDao().setPendingRemoval(messageIds, false)
        db.folderDao().refreshAllCounts()
    }

    /**
     * Puts back anything that was hidden when the process last died.
     *
     * A row is hidden before its removal is attempted, so a crash — or a swipe
     * a moment before the app was killed — would otherwise leave mail present
     * but invisible, with nothing left running to finish the job.
     */
    suspend fun releaseAbandonedRemovals() {
        db.messageDao().clearPendingRemovals()
        db.folderDao().refreshAllCounts()
    }

    suspend fun setSeen(messageIds: List<Long>, seen: Boolean) {
        forEachRemoteGroup(messageIds) { account, folder, uids ->
            pool.use(account.id) { it.setFlags(folder.path, uids, Flags.Flag.SEEN, seen) }
        }
        db.messageDao().setSeen(messageIds, seen)
        messageIds.forEach { notifier.cancel(it) }
        refreshCountsFor(messageIds)
    }

    suspend fun setFlagged(messageIds: List<Long>, flagged: Boolean) {
        forEachRemoteGroup(messageIds) { account, folder, uids ->
            pool.use(account.id) { it.setFlags(folder.path, uids, Flags.Flag.FLAGGED, flagged) }
        }
        db.messageDao().setFlagged(messageIds, flagged)
    }

    suspend fun archive(messageIds: List<Long>, allowUndo: Boolean = true) =
        optimistically(messageIds, UndoKind.ARCHIVE.takeIf { allowUndo }) {
        val messages = db.messageDao().getAll(messageIds)
        messages.groupBy { it.accountId }.forEach { (accountId, group) ->
            val account = db.accountDao().get(accountId) ?: return@forEach
            val archive = account.archiveFolder
                ?: throw MailException("No archive folder configured for ${account.displayName}")
            moveMessagesToPath(account, group.map { it.id }, archive)
        }
    }

    suspend fun moveToTrash(messageIds: List<Long>, allowUndo: Boolean = true) =
        optimistically(messageIds, UndoKind.TRASH.takeIf { allowUndo }) {
        val messages = db.messageDao().getAll(messageIds)
        messages.groupBy { it.accountId }.forEach { (accountId, group) ->
            val account = db.accountDao().get(accountId) ?: return@forEach
            val trash = account.trashFolder
            if (trash == null) {
                removeFromServer(group.map { it.id })
            } else {
                moveMessagesToPath(account, group.map { it.id }, trash)
            }
        }
    }

    /**
     * Moves mail into [targetFolderId], which may belong to a different account
     * than the mail is in now.
     */
    suspend fun moveMessages(messageIds: List<Long>, targetFolderId: Long) {
        val target = db.folderDao().get(targetFolderId) ?: return
        if (target.isLocal) {
            // A local move keeps the row, so it must not be hidden as a removal.
            moveToLocalFolder(messageIds, targetFolderId)
            return
        }
        val account = db.accountDao().get(target.accountId) ?: return
        optimistically(messageIds, UndoKind.MOVE) {
            val messages = db.messageDao().getAll(messageIds)
            val (sameMailbox, otherMailbox) = messages.partition { it.accountId == account.id }
            if (sameMailbox.isNotEmpty()) {
                moveMessagesToPath(account, sameMailbox.map { it.id }, target.path)
            }
            if (otherMailbox.isNotEmpty()) {
                transferToMailbox(otherMailbox, account, target, keepSource = false)
            }
            // Show the mail where it landed rather than waiting for the next
            // scheduled sync of that folder.
            runCatching { syncFolder(target.id) }
        }
    }

    /**
     * Copies mail into [targetFolderId], leaving the originals alone. As with a
     * move, the destination may belong to another account.
     */
    suspend fun copyMessages(messageIds: List<Long>, targetFolderId: Long) {
        val target = db.folderDao().get(targetFolderId) ?: return
        if (target.isLocal) {
            copyToLocalFolder(messageIds, targetFolderId)
            return
        }
        val account = db.accountDao().get(target.accountId) ?: return
        val messages = db.messageDao().getAll(messageIds)
        val (sameMailbox, otherMailbox) = messages.partition { it.accountId == account.id }
        if (sameMailbox.isNotEmpty()) {
            copyMessagesToPath(account, sameMailbox.map { it.id }, target.path)
        }
        if (otherMailbox.isNotEmpty()) {
            transferToMailbox(otherMailbox, account, target, keepSource = true)
        }
        runCatching { syncFolder(target.id) }
    }

    /**
     * Carries mail from one account to another.
     *
     * IMAP cannot copy between servers, so the message is downloaded whole and
     * appended to the destination. The source copy is only removed once the
     * append has been acknowledged, and a message whose source could not be
     * fetched is left where it is: losing mail in transit is far worse than a
     * move that did not happen.
     */
    private suspend fun transferToMailbox(
        messages: List<MessageEntity>,
        targetAccount: AccountEntity,
        target: FolderEntity,
        keepSource: Boolean
    ) {
        val failures = mutableListOf<String>()

        messages.groupBy { it.folderId }.forEach { (folderId, group) ->
            val source = db.folderDao().get(folderId) ?: return@forEach
            val sourceAccount = db.accountDao().get(source.accountId) ?: return@forEach
            val delivered = mutableListOf<MessageEntity>()

            group.forEach { message ->
                val raw = runCatching { rawSourceOf(sourceAccount, source, message) }.getOrNull()
                if (raw == null) {
                    failures += message.subject.ifBlank { "(no subject)" }
                    return@forEach
                }
                val appended = runCatching {
                    pool.use(targetAccount.id) { it.append(target.path, raw, message.seen) }
                }
                if (appended.isSuccess) delivered += message
                else failures += message.subject.ifBlank { "(no subject)" }
            }

            if (keepSource || delivered.isEmpty()) return@forEach

            // The mail is on the other server now, so it must not stay here.
            val remoteUids = delivered.filter { !it.isLocal }.map { it.uid }
            if (remoteUids.isNotEmpty()) {
                runCatching { pool.use(sourceAccount.id) { it.deleteMessages(source.path, remoteUids) } }
                    .onFailure {
                        failures += "could not remove ${remoteUids.size} from ${source.displayName}"
                        return@forEach
                    }
            }
            delivered.mapNotNull { it.rawFilePath }.forEach { runCatching { File(it).delete() } }
            db.messageDao().deleteAll(delivered.map { it.id })
            delivered.forEach { notifier.cancel(it.id) }
            db.folderDao().refreshCounts(folderId)
        }

        if (failures.isNotEmpty()) {
            throw MailException(
                "Could not transfer ${failures.size} message(s) to ${targetAccount.email}"
            )
        }
    }

    /** A message's full source, from the device when it is there and the server otherwise. */
    private suspend fun rawSourceOf(
        account: AccountEntity,
        folder: FolderEntity,
        message: MessageEntity
    ): ByteArray? {
        message.rawFilePath?.let(::File)?.takeIf { it.exists() }?.let { return it.readBytes() }
        if (message.isLocal) return null
        return pool.use(account.id) { it.fetchRaw(folder.path, message.uid) }
    }

    private suspend fun moveMessagesToPath(
        account: AccountEntity,
        messageIds: List<Long>,
        targetPath: String
    ) {
        val messages = db.messageDao().getAll(messageIds).filter { it.accountId == account.id }
        val local = messages.filter { it.isLocal }
        val remote = messages.filter { !it.isLocal }

        remote.groupBy { it.folderId }.forEach { (folderId, group) ->
            val folder = db.folderDao().get(folderId) ?: return@forEach
            if (folder.path == targetPath) return@forEach
            pool.use(account.id) { it.moveMessages(folder.path, group.map { m -> m.uid }, targetPath) }
            db.messageDao().deleteAll(group.map { it.id })
            db.folderDao().refreshCounts(folderId)
        }

        // A local message going back to the server is an APPEND of its stored .eml.
        local.forEach { message ->
            val raw = message.rawFilePath?.let(::File)?.takeIf { it.exists() }?.readBytes()
                ?: return@forEach
            pool.use(account.id) { it.append(targetPath, raw, message.seen) }
            db.messageDao().delete(message.id)
            runCatching { File(message.rawFilePath).delete() }
        }

        db.folderDao().getByPath(account.id, targetPath)?.let { db.folderDao().refreshCounts(it.id) }
    }

    /** Server-side copy, leaving the originals where they are. */
    private suspend fun copyMessagesToPath(
        account: AccountEntity,
        messageIds: List<Long>,
        targetPath: String
    ) {
        val messages = db.messageDao().getAll(messageIds).filter { it.accountId == account.id }

        messages.filter { !it.isLocal }.groupBy { it.folderId }.forEach { (folderId, group) ->
            val folder = db.folderDao().get(folderId) ?: return@forEach
            if (folder.path == targetPath) return@forEach
            pool.use(account.id) { it.copyMessages(folder.path, group.map { m -> m.uid }, targetPath) }
        }

        // A device-only message has no server copy to duplicate, so its stored
        // source is uploaded instead.
        messages.filter { it.isLocal }.forEach { message ->
            val raw = message.rawFilePath?.let(::File)?.takeIf { it.exists() }?.readBytes()
                ?: return@forEach
            pool.use(account.id) { it.append(targetPath, raw, message.seen) }
        }

        db.folderDao().getByPath(account.id, targetPath)?.let { db.folderDao().refreshCounts(it.id) }
    }

    suspend fun deletePermanently(messageIds: List<Long>, allowUndo: Boolean = true) =
        optimistically(messageIds, UndoKind.DELETE.takeIf { allowUndo }) {
            removeFromServer(messageIds)
        }

    private suspend fun removeFromServer(messageIds: List<Long>) {
        val messages = db.messageDao().getAll(messageIds)
        messages.filter { !it.isLocal }.groupBy { it.folderId }.forEach { (folderId, group) ->
            val folder = db.folderDao().get(folderId) ?: return@forEach
            val account = db.accountDao().get(folder.accountId) ?: return@forEach
            runCatching {
                pool.use(account.id) { it.deleteMessages(folder.path, group.map { m -> m.uid }) }
            }
        }
        messages.mapNotNull { it.rawFilePath }.forEach { runCatching { File(it).delete() } }
        db.messageDao().deleteAll(messageIds)
        messageIds.forEach { notifier.cancel(it) }
        refreshCountsFor(messageIds)
    }

    // -------------------------------------------------------------- folders

    suspend fun createRemoteFolder(accountId: Long, path: String) {
        pool.use(accountId) { it.createFolder(path) }
        refreshFolders(accountId)
    }

    suspend fun deleteRemoteFolder(folderId: Long) {
        val folder = db.folderDao().get(folderId) ?: return
        if (folder.isLocal) {
            deleteLocalFolder(folderId)
            return
        }
        pool.use(folder.accountId) { it.deleteFolder(folder.path) }
        db.folderDao().delete(folderId)
    }

    suspend fun renameRemoteFolder(folderId: Long, newPath: String) {
        val folder = db.folderDao().get(folderId) ?: return
        if (folder.isLocal) {
            db.folderDao().update(
                folder.copy(path = localPath(newPath), displayName = newPath)
            )
            return
        }
        pool.use(folder.accountId) { it.renameFolder(folder.path, newPath) }
        refreshFolders(folder.accountId)
    }

    /**
     * Moves a folder [delta] places within its account.
     *
     * The whole account's order is written out on the first move: until then the
     * folders sort automatically, and swapping only two of them would drag that
     * pair above everything else.
     */
    suspend fun moveFolder(folderId: Long, delta: Int) {
        val folder = db.folderDao().get(folderId) ?: return
        val ordered = db.folderDao().forAccount(folder.accountId).toMutableList()
        val index = ordered.indexOfFirst { it.id == folderId }
        val target = index + delta
        if (index < 0 || target !in ordered.indices) return
        ordered.add(target, ordered.removeAt(index))
        ordered.forEachIndexed { position, entry ->
            if (entry.sortOverride != position) {
                db.folderDao().update(entry.copy(sortOverride = position))
            }
        }
    }

    /** Hides a folder on this device; the folder itself is left alone on the server. */
    suspend fun setFolderHidden(folderId: Long, hidden: Boolean) {
        val folder = db.folderDao().get(folderId) ?: return
        db.folderDao().update(folder.copy(hidden = hidden))
    }

    /** Restores the automatic ordering for an account. */
    suspend fun resetFolderOrder(accountId: Long) {
        db.folderDao().forAccount(accountId)
            .filter { it.sortOverride != null }
            .forEach { db.folderDao().update(it.copy(sortOverride = null)) }
    }

    suspend fun setFolderSyncEnabled(folderId: Long, enabled: Boolean) {
        val folder = db.folderDao().get(folderId) ?: return
        db.folderDao().update(folder.copy(syncEnabled = enabled))
    }

    /** Marks everything currently unread in a folder as read, locally and on the server. */
    suspend fun markFolderRead(folderId: Long): Int {
        val ids = db.messageDao().unreadIdsIn(folderId)
        if (ids.isEmpty()) return 0
        setSeen(ids, true)
        return ids.size
    }

    suspend fun createLocalFolder(accountId: Long, name: String): Long {
        val path = localPath(name)
        db.folderDao().getByPath(accountId, path)?.let {
            throw MailException("Local folder \"$name\" already exists")
        }
        return db.folderDao().insert(
            FolderEntity(
                accountId = accountId,
                path = path,
                displayName = name,
                type = FolderType.LOCAL.name,
                isLocal = true,
                syncEnabled = false,
                position = 10_000
            )
        )
    }

    suspend fun deleteLocalFolder(folderId: Long) {
        val folder = db.folderDao().get(folderId) ?: return
        if (!folder.isLocal) return
        db.folderDao().delete(folderId)
        runCatching { localFolderDir(folder).deleteRecursively() }
    }

    /**
     * Downloads each message in full, stores the .eml on the device and removes
     * the server copy — the point of a local folder is that it survives the
     * server no longer having the mail.
     */
    suspend fun moveToLocalFolder(messageIds: List<Long>, localFolderId: Long) {
        val target = db.folderDao().get(localFolderId) ?: return
        if (!target.isLocal) return
        val all = db.messageDao().getAll(messageIds).filter { it.folderId != localFolderId }

        // Mail already on the device only needs its row and its file to change
        // hands; there is no server copy left to fetch or delete.
        all.filter { it.isLocal }.forEach { message ->
            runCatching { relocateLocalFile(message, target) }
        }

        all.filter { !it.isLocal }.groupBy { it.folderId }.forEach { (folderId, group) ->
            val source = db.folderDao().get(folderId) ?: return@forEach
            val account = db.accountDao().get(source.accountId) ?: return@forEach
            // Only remove the server copy of messages whose full source actually
            // landed on disk; a failed download must not lose the mail.
            val archived = group.mapNotNull { message ->
                val stored = runCatching { storeLocally(account, source, target, message) }
                    .getOrNull() ?: return@mapNotNull null
                db.messageDao().update(stored.copy(id = message.id))
                message.uid
            }
            if (archived.isNotEmpty()) {
                runCatching {
                    pool.use(account.id) { it.deleteMessages(source.path, archived) }
                }
            }
            db.folderDao().refreshCounts(folderId)
        }
        db.folderDao().refreshCounts(localFolderId)
    }

    /**
     * Puts a copy in a local folder and leaves the server copy where it is.
     *
     * The copy is a row of its own, so reading, flagging or deleting it does
     * not touch the original.
     */
    suspend fun copyToLocalFolder(messageIds: List<Long>, localFolderId: Long) {
        val target = db.folderDao().get(localFolderId) ?: return
        if (!target.isLocal) return
        db.messageDao().getAll(messageIds)
            .filter { it.folderId != localFolderId }
            .groupBy { it.folderId }
            .forEach { (folderId, group) ->
                val source = db.folderDao().get(folderId) ?: return@forEach
                val account = db.accountDao().get(source.accountId) ?: return@forEach
                group.forEach { message ->
                    runCatching {
                        val stored = if (message.isLocal) duplicateLocally(message, target)
                        else storeLocally(account, source, target, message)
                        stored?.let { db.messageDao().insert(it.copy(id = 0)) }
                    }
                }
            }
        db.folderDao().refreshCounts(localFolderId)
    }

    /**
     * Downloads a message in full and writes its .eml under [target].
     *
     * Returns the row the caller should persist — with the id left at the
     * original's, so a move updates and a copy inserts — or null when the full
     * source could not be fetched, which is what stops a failed download from
     * losing the mail.
     */
    private suspend fun storeLocally(
        account: AccountEntity,
        source: FolderEntity,
        target: FolderEntity,
        message: MessageEntity
    ): MessageEntity? {
        val raw = pool.use(account.id) { it.fetchRaw(source.path, message.uid) }
            ?: return null
        val body = message.takeIf { it.bodyDownloaded }
            ?: pool.use(account.id) { client ->
                client.fetchBody(source.path, message.uid)?.let { fetched ->
                    message.copy(
                        bodyPlain = fetched.plain,
                        bodyHtml = fetched.html,
                        bodyDownloaded = true,
                        preview = MimeUtil.preview(fetched.plain, fetched.html)
                    )
                }
            } ?: message

        val file = File(localFolderDir(target), "${message.uid}_${System.currentTimeMillis()}.eml")
        file.parentFile?.mkdirs()
        file.writeBytes(raw)

        return body.copy(
            folderId = target.id,
            uid = nextLocalUid(target.id),
            isLocal = true,
            rawFilePath = file.absolutePath
        )
    }

    /** Moves an already-local message's row and its stored .eml to another local folder. */
    private suspend fun relocateLocalFile(message: MessageEntity, target: FolderEntity) {
        val existing = message.rawFilePath?.let(::File)?.takeIf { it.exists() }
        val moved = existing?.let { from ->
            val to = File(localFolderDir(target), from.name)
            to.parentFile?.mkdirs()
            if (from.renameTo(to)) to else from.copyTo(to, overwrite = true).also { from.delete() }
        }
        db.messageDao().update(
            message.copy(
                folderId = target.id,
                uid = nextLocalUid(target.id),
                rawFilePath = moved?.absolutePath ?: message.rawFilePath
            )
        )
        db.folderDao().refreshCounts(message.folderId)
    }

    /** A second copy of an already-local message, with its own .eml on disk. */
    private suspend fun duplicateLocally(
        message: MessageEntity,
        target: FolderEntity
    ): MessageEntity? {
        val from = message.rawFilePath?.let(::File)?.takeIf { it.exists() } ?: return null
        val to = File(localFolderDir(target), "${System.currentTimeMillis()}_${from.name}")
        to.parentFile?.mkdirs()
        from.copyTo(to, overwrite = true)
        return message.copy(
            folderId = target.id,
            uid = nextLocalUid(target.id),
            isLocal = true,
            rawFilePath = to.absolutePath
        )
    }

    /**
     * Local rows carry a negative uid so they cannot collide with a server's.
     * The folder's (folderId, uid) index is unique, so each one has to be lower
     * than every uid already in that folder.
     */
    private suspend fun nextLocalUid(folderId: Long): Long =
        ((db.messageDao().minUid(folderId) ?: 0L) - 1).coerceAtMost(-1L)

    /** Writes a message's raw source to the app's files dir and returns the file. */
    suspend fun downloadRaw(messageId: Long): File? = withContext(Dispatchers.IO) {
        val message = db.messageDao().get(messageId) ?: return@withContext null
        message.rawFilePath?.let(::File)?.takeIf { it.exists() }?.let { return@withContext it }
        val folder = db.folderDao().get(message.folderId) ?: return@withContext null
        val account = db.accountDao().get(message.accountId) ?: return@withContext null
        val raw = pool.use(account.id) { it.fetchRaw(folder.path, message.uid) }
            ?: return@withContext null
        val dir = File(context.filesDir, "raw/${account.id}").apply { mkdirs() }
        val name = MimeUtil.sanitizeFileName(
            message.subject.ifBlank { "message-$messageId" }
        ) + ".eml"
        val file = File(dir, "${messageId}_$name")
        file.writeBytes(raw)
        db.messageDao().update(message.copy(rawFilePath = file.absolutePath))
        file
    }

    /**
     * Fetches the bodies of [messageIds] one at a time, so opening any of them
     * is instant and works offline.
     *
     * Runs at the back of the queue by design: [ImapPool] hands the connection
     * out in order, so releasing it between messages and pausing in between
     * leaves a user-initiated fetch waiting for one message at most. Failures
     * are swallowed — a preload that does not happen costs nothing.
     */
    suspend fun prefetchBodies(messageIds: List<Long>) {
        for (id in messageIds) {
            val message = db.messageDao().get(id) ?: continue
            if (message.bodyDownloaded || message.isLocal) continue
            runCatching { ensureBody(id) }
            delay(PREFETCH_GAP_MILLIS)
        }
    }

    /** Loads the body (and attachment list) on demand when a message is opened. */
    suspend fun ensureBody(messageId: Long): MessageEntity? {
        val message = db.messageDao().get(messageId) ?: return null
        if (message.bodyDownloaded || message.isLocal) return message
        val folder = db.folderDao().get(message.folderId) ?: return message
        val account = db.accountDao().get(message.accountId) ?: return message
        val body = runCatching { pool.use(account.id) { it.fetchBody(folder.path, message.uid) } }
            .getOrNull() ?: return message

        val updated = message.copy(
            bodyPlain = body.plain,
            bodyHtml = body.html,
            bodyDownloaded = true,
            hasAttachments = body.attachments.any { !it.isInline },
            preview = MimeUtil.preview(body.plain, body.html)
        )
        db.messageDao().update(updated)
        db.attachmentDao().clearFor(messageId)
        db.attachmentDao().insertAll(
            body.attachments.map {
                AttachmentEntity(
                    messageId = messageId,
                    partId = it.partId,
                    fileName = it.fileName,
                    mimeType = it.mimeType,
                    sizeBytes = it.sizeBytes,
                    isInline = it.isInline,
                    contentId = it.contentId
                )
            }
        )
        return updated
    }

    /**
     * Fetches every attachment on [messageIds] and saves it to the device's
     * Downloads folder.
     *
     * Bodies are fetched first where they have not been already: until a
     * message has been opened, its attachment list is not known — the envelope
     * only says whether there are any.
     */
    suspend fun saveAttachmentsToDevice(messageIds: List<Long>): SavedAttachments {
        var saved = 0
        var missing = 0
        var withNone = 0

        for (messageId in messageIds) {
            runCatching { ensureBody(messageId) }
            val attachments = db.attachmentDao().forMessage(messageId).filter { !it.isInline }
            if (attachments.isEmpty()) {
                withNone++
                continue
            }
            for (attachment in attachments) {
                val file = runCatching { downloadAttachment(attachment.id) }.getOrNull()
                if (file == null) {
                    missing++
                    continue
                }
                val name = withContext(Dispatchers.IO) {
                    DeviceDownloads.save(
                        context,
                        file,
                        MimeUtil.sanitizeFileName(attachment.fileName),
                        attachment.mimeType
                    )
                }
                if (name == null) missing++ else saved++
            }
        }
        return SavedAttachments(saved, missing, withNone)
    }

    suspend fun downloadAttachment(attachmentId: Long): File? = withContext(Dispatchers.IO) {
        val attachment = db.attachmentDao().get(attachmentId) ?: return@withContext null
        attachment.localPath?.let(::File)?.takeIf { it.exists() }?.let { return@withContext it }
        val message = db.messageDao().get(attachment.messageId) ?: return@withContext null
        val folder = db.folderDao().get(message.folderId) ?: return@withContext null
        val bytes = pool.use(message.accountId) {
            it.fetchAttachment(folder.path, message.uid, attachment.partId)
        } ?: return@withContext null
        val dir = File(context.filesDir, "attachments/${message.id}").apply { mkdirs() }
        val file = File(dir, MimeUtil.sanitizeFileName(attachment.fileName))
        file.writeBytes(bytes)
        db.attachmentDao().update(attachment.copy(localPath = file.absolutePath))
        file
    }

    // ------------------------------------------------------------- outgoing

    suspend fun sendOutbox() {
        db.outboxDao().pending().forEach { item ->
            val account = db.accountDao().get(item.accountId) ?: return@forEach
            val secret = runCatching { tokenStore.smtpSecret(account) }.getOrNull()
                ?: return@forEach
            runCatching { smtp.send(account, secret, item) }
                .onSuccess { raw ->
                    account.sentFolder?.let { sent ->
                        runCatching { pool.use(account.id) { it.append(sent, raw, seen = true) } }
                    }
                    db.outboxDao().delete(item.id)
                }
                .onFailure { error ->
                    db.outboxDao().update(
                        item.copy(attempts = item.attempts + 1, lastError = error.message)
                    )
                }
        }
    }

    // ------------------------------------------------------- retroactive rules

    /**
     * Replays the current rule set over already-cached mail in [folderId].
     * Returns the number of messages that a rule acted on.
     */
    suspend fun applyRulesToFolder(folderId: Long): Int {
        val folder = db.folderDao().get(folderId) ?: return 0
        val account = db.accountDao().get(folder.accountId) ?: return 0
        val rules = db.ruleDao().enabledRules()
        if (rules.isEmpty()) return 0

        val uids = db.messageDao().uidsIn(folderId)
        var affected = 0
        uids.chunked(100).forEach { chunk ->
            chunk.forEach { uid ->
                val message = db.messageDao().getByUid(folderId, uid) ?: return@forEach
                val plan = ruleEngine.plan(MatchContext.of(message, folder.path), rules)
                if (!plan.matched) return@forEach
                affected++
                runCatching { applyPlanToExisting(account, folder, message, plan) }
            }
        }
        db.folderDao().refreshCounts(folderId)
        return affected
    }

    private suspend fun applyPlanToExisting(
        account: AccountEntity,
        folder: FolderEntity,
        message: MessageEntity,
        plan: RulePlan
    ) {
        if (plan.markRead) setSeen(listOf(message.id), true)
        if (plan.markUnread) setSeen(listOf(message.id), false)
        plan.flag?.let { setFlagged(listOf(message.id), it) }
        // Copies run before the relocation below, which may take the message
        // off the server entirely.
        plan.copies.forEach { action ->
            val arg = action.arg ?: return@forEach
            when (action.type) {
                ActionType.COPY_TO_FOLDER ->
                    runCatching { copyMessagesToPath(account, listOf(message.id), arg) }
                ActionType.COPY_TO_LOCAL -> localFolderFor(account.id, arg)?.let { target ->
                    runCatching { copyToLocalFolder(listOf(message.id), target.id) }
                }
                else -> Unit
            }
        }
        plan.relocation?.let { action ->
            when (action.type) {
                ActionType.ARCHIVE -> account.archiveFolder?.let {
                    moveMessagesToPath(account, listOf(message.id), it)
                }
                // A rule runs unattended, so there is no one to offer the
                // moment of second thought to.
                ActionType.MOVE_TO_TRASH -> moveToTrash(listOf(message.id), allowUndo = false)
                ActionType.DELETE_PERMANENTLY ->
                    deletePermanently(listOf(message.id), allowUndo = false)
                ActionType.MOVE_TO_FOLDER -> action.arg?.let {
                    moveMessagesToPath(account, listOf(message.id), it)
                }
                ActionType.MOVE_TO_LOCAL -> action.arg?.let { name ->
                    localFolderFor(account.id, name)?.let { target ->
                        moveToLocalFolder(listOf(message.id), target.id)
                    }
                }
                else -> Unit
            }
        }
        val at = System.currentTimeMillis()
        plan.matchedRules.forEach { rule ->
            db.ruleDao().recordMatch(rule.id, at)
            db.ruleDao().log(
                RuleLogEntity(
                    ruleId = rule.id,
                    ruleName = rule.name,
                    accountId = account.id,
                    subject = message.subject,
                    fromAddress = message.fromAddress,
                    actionsTaken = plan.summary(),
                    at = at
                )
            )
        }
    }

    // ------------------------------------------------------------- internals

    private suspend fun moveUidsToLocal(
        account: AccountEntity,
        folder: FolderEntity,
        uids: List<Long>,
        localName: String
    ) {
        val target = localFolderFor(account.id, localName) ?: return
        val ids = uids.mapNotNull { db.messageDao().getByUid(folder.id, it)?.id }
        moveToLocalFolder(ids, target.id)
    }

    private suspend fun copyUidsToLocal(
        account: AccountEntity,
        folder: FolderEntity,
        uids: List<Long>,
        localName: String
    ) {
        val target = localFolderFor(account.id, localName) ?: return
        val ids = uids.mapNotNull { db.messageDao().getByUid(folder.id, it)?.id }
        copyToLocalFolder(ids, target.id)
    }

    /**
     * The account's local folder of that name, created on demand: a rule
     * naming a folder that has since been deleted should still work.
     */
    private suspend fun localFolderFor(accountId: Long, name: String): FolderEntity? =
        db.folderDao().getByPath(accountId, localPath(name))
            ?: db.folderDao().get(createLocalFolder(accountId, name))

    private suspend fun forEachRemoteGroup(
        messageIds: List<Long>,
        block: suspend (AccountEntity, FolderEntity, List<Long>) -> Unit
    ) {
        db.messageDao().getAll(messageIds)
            .filter { !it.isLocal }
            .groupBy { it.folderId }
            .forEach { (folderId, group) ->
                val folder = db.folderDao().get(folderId) ?: return@forEach
                val account = db.accountDao().get(folder.accountId) ?: return@forEach
                runCatching { block(account, folder, group.map { it.uid }) }
            }
    }

    private suspend fun refreshCountsFor(messageIds: List<Long>) {
        db.messageDao().getAll(messageIds).map { it.folderId }.distinct()
            .forEach { db.folderDao().refreshCounts(it) }
        db.folderDao().refreshAllCounts()
    }

    private fun localFolderDir(folder: FolderEntity) =
        File(context.filesDir, "local/${folder.accountId}/${folder.id}")

    companion object {
        const val INITIAL_FETCH = 100
        const val INCREMENTAL_FETCH = 200
        const val PAGE_SIZE = 50
        /** How long a removal waits, so it can be taken back rather than reversed. */
        const val UNDO_WINDOW_MILLIS = 5_000L
        /** How many unread messages the list screen preloads ahead of the user. */
        const val PREFETCH_LIMIT = 30
        private const val PREFETCH_GAP_MILLIS = 400L
        private const val FLAG_WINDOW = 200

        fun localPath(name: String) = "local/$name"
        fun localName(path: String) = path.removePrefix("local/")
    }
}

private fun FetchedMessage.toEntity(accountId: Long, folderId: Long) = MessageEntity(
    accountId = accountId,
    folderId = folderId,
    uid = uid,
    messageIdHeader = messageIdHeader,
    subject = subject,
    fromName = fromName,
    fromAddress = fromAddress,
    senderDomain = fromAddress?.substringAfterLast('@')?.lowercase()?.takeIf { it.isNotBlank() },
    toList = to.joinAddresses(),
    ccList = cc.joinAddresses(),
    bccList = bcc.joinAddresses(),
    replyTo = replyTo,
    sentAt = sentAt,
    receivedAt = receivedAt,
    seen = seen,
    flagged = flagged,
    answered = answered,
    draft = draft,
    hasAttachments = likelyHasAttachments,
    sizeBytes = sizeBytes,
    preview = "",
    headersJson = Json.encodeHeaders(headers),
    isLocal = false
)
