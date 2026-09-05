package de.uwumail.sync

import android.content.Context
import de.uwumail.core.ActionType
import de.uwumail.core.FolderType
import de.uwumail.core.Json
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import javax.mail.Flags

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
                if (action.type == ActionType.COPY_TO_FOLDER) {
                    copies.getOrPut(target) { mutableListOf() } += message.uid
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

    suspend fun archive(messageIds: List<Long>) {
        val messages = db.messageDao().getAll(messageIds)
        messages.groupBy { it.accountId }.forEach { (accountId, group) ->
            val account = db.accountDao().get(accountId) ?: return@forEach
            val archive = account.archiveFolder
                ?: throw MailException("No archive folder configured for ${account.displayName}")
            moveMessagesToPath(account, group.map { it.id }, archive)
        }
    }

    suspend fun moveToTrash(messageIds: List<Long>) {
        val messages = db.messageDao().getAll(messageIds)
        messages.groupBy { it.accountId }.forEach { (accountId, group) ->
            val account = db.accountDao().get(accountId) ?: return@forEach
            val trash = account.trashFolder
            if (trash == null) {
                deletePermanently(group.map { it.id })
            } else {
                moveMessagesToPath(account, group.map { it.id }, trash)
            }
        }
    }

    suspend fun moveMessages(messageIds: List<Long>, targetFolderId: Long) {
        val target = db.folderDao().get(targetFolderId) ?: return
        if (target.isLocal) {
            moveToLocalFolder(messageIds, targetFolderId)
            return
        }
        val account = db.accountDao().get(target.accountId) ?: return
        moveMessagesToPath(account, messageIds, target.path)
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

    suspend fun deletePermanently(messageIds: List<Long>) {
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
        val messages = db.messageDao().getAll(messageIds).filter { !it.isLocal }
        messages.groupBy { it.folderId }.forEach { (folderId, group) ->
            val source = db.folderDao().get(folderId) ?: return@forEach
            val account = db.accountDao().get(source.accountId) ?: return@forEach
            // Only remove the server copy of messages whose full source actually
            // landed on disk; a failed download must not lose the mail.
            val archived = group.mapNotNull { message ->
                runCatching { archiveOneLocally(account, source, target, message) }
                    .getOrDefault(false)
                    .takeIf { it }
                    ?.let { message.uid }
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

    /** Returns true only when the full message source reached local storage. */
    private suspend fun archiveOneLocally(
        account: AccountEntity,
        source: FolderEntity,
        target: FolderEntity,
        message: MessageEntity
    ): Boolean {
        val raw = pool.use(account.id) { it.fetchRaw(source.path, message.uid) }
            ?: return false
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

        val nextUid = (db.messageDao().minUid(target.id) ?: 0L) - 1
        db.messageDao().update(
            body.copy(
                folderId = target.id,
                uid = if (nextUid < 0) nextUid else -1L,
                isLocal = true,
                rawFilePath = file.absolutePath
            )
        )
        return true
    }

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
        plan.relocation?.let { action ->
            when (action.type) {
                ActionType.ARCHIVE -> account.archiveFolder?.let {
                    moveMessagesToPath(account, listOf(message.id), it)
                }
                ActionType.MOVE_TO_TRASH -> moveToTrash(listOf(message.id))
                ActionType.DELETE_PERMANENTLY -> deletePermanently(listOf(message.id))
                ActionType.MOVE_TO_FOLDER -> action.arg?.let {
                    moveMessagesToPath(account, listOf(message.id), it)
                }
                ActionType.MOVE_TO_LOCAL -> action.arg?.let { name ->
                    db.folderDao().getByPath(account.id, localPath(name))?.let { target ->
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
        val target = db.folderDao().getByPath(account.id, localPath(localName))
            ?: db.folderDao().get(createLocalFolder(account.id, localName))
            ?: return
        val ids = uids.mapNotNull { db.messageDao().getByUid(folder.id, it)?.id }
        moveToLocalFolder(ids, target.id)
    }

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
