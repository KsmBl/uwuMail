package de.uwumail.ui.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.uwumail.R
import de.uwumail.core.DeviceDownloads
import de.uwumail.core.SavedAttachments
import de.uwumail.core.FolderType
import de.uwumail.core.SwipeAction
import de.uwumail.core.settled
import de.uwumail.data.settings.AppSettings
import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.FolderEntity
import de.uwumail.data.db.MessageSummary
import de.uwumail.data.db.effectiveType
import de.uwumail.data.db.isBinFor
import de.uwumail.di.AppContainer
import de.uwumail.sync.SyncManager
import de.uwumail.sync.Undoable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Actions offered when a folder in the drawer is long-pressed. */
enum class FolderAction {
    MOVE_UP, MOVE_DOWN, HIDE, SHOW, TOGGLE_SYNC, TOGGLE_NOTIFY, MARK_READ, RESET_ORDER,
    /** Deletes everything in the folder for good; offered on the bin alone. */
    EMPTY
}

/** What the message list is currently showing. */
sealed interface MailTarget {
    /** Every folder of one role, across all accounts. */
    data class Unified(val type: FolderType) : MailTarget
    data class Folder(val id: Long) : MailTarget

    /** Everything cached, everywhere, matching whatever was typed. */
    data object Search : MailTarget

    /** One conversation, opened from the row standing for it. */
    data class Thread(val id: String, val title: String) : MailTarget

    companion object {
        val INBOXES = Unified(FolderType.INBOX)

        /**
         * Every account's Sent folder. Named for what it holds: everywhere else
         * on a phone an outbox is mail that has not gone yet, which is a real
         * and different thing — see [de.uwumail.data.db.OutboxEntity].
         */
        val SENT = Unified(FolderType.SENT)
        val DELETED = Unified(FolderType.TRASH)

        /** The unified rows shown at the top of the drawer, in order. */
        val UNIFIED = listOf(
            INBOXES to R.string.all_inboxes,
            SENT to R.string.all_sent,
            DELETED to R.string.all_deleted
        )
    }
}

data class MailUiState(
    val accounts: List<AccountEntity> = emptyList(),
    val folders: List<FolderEntity> = emptyList(),
    val target: MailTarget = MailTarget.INBOXES,
    val currentFolder: FolderEntity? = null,
    val messages: List<MessageSummary> = emptyList(),
    val selection: Set<Long> = emptySet(),
    val query: String = "",
    val syncing: Boolean = false,
    /** Drives the pull-to-refresh indicator; must flip true then false. */
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    /** A user-initiated action is waiting on the server. */
    val busy: Boolean = false,
    val error: String? = null,
    val status: String? = null,
    /**
     * Removals that can still be taken back, oldest first. Several can be in
     * flight at once — swiping through a few messages is exactly how — so they
     * stack rather than replacing one another.
     */
    val undos: List<Undoable> = emptyList(),
    /** What a swipe across a row does, per direction. */
    val swipeRight: SwipeAction = SwipeAction.ARCHIVE,
    val swipeLeft: SwipeAction = SwipeAction.SELECT,
    /** How far a swipe must travel to count, as a fraction of the row's width. */
    val swipeThreshold: Float = AppSettings().swipeThresholdFraction
) {
    val inSelectionMode: Boolean get() = selection.isNotEmpty()

    /** Whether this list is the Drafts folder, where a tap means "carry on writing". */
    val showsDrafts: Boolean
        get() = currentFolder?.effectiveType == FolderType.DRAFTS.name

    /** Folders the drawer shows; hidden ones are kept out of every list. */
    val visibleFolders: List<FolderEntity> get() = folders.filter { !it.hidden }

    /**
     * Folders that can receive a move or a copy: every folder of every account
     * except the one we are looking at. Mail can cross mailboxes, so the other
     * accounts' folders belong in the list.
     */
    fun moveTargets(): List<FolderEntity> = visibleFolders
        .filter { it.selectable }
        .filter { it.id != currentFolder?.id }

    /** Folders that are where their own account puts deleted mail. */
    fun binFolderIds(): Set<Long> = folders
        .filter { folder -> folder.isBinFor(accounts.firstOrNull { it.id == folder.accountId }) }
        .mapTo(HashSet()) { it.id }

    /**
     * The selection split into what is already in the bin and what is not.
     *
     * One button covers both: a mixed selection is exactly what happens when
     * somebody tidies up across folders, and refusing to act on it — or quietly
     * doing nothing to half of it — is worse than doing each part properly.
     */
    fun selectionByBin(): Pair<List<Long>, List<Long>> {
        val bins = binFolderIds()
        val selected = messages.filter { it.id in selection }
        val (inBin, elsewhere) = selected.partition { it.folderId in bins }
        return inBin.map { it.id } to elsewhere.map { it.id }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MailViewModel(private val container: AppContainer) : ViewModel() {

    private val target = MutableStateFlow<MailTarget>(MailTarget.INBOXES)
    private val selection = MutableStateFlow<Set<Long>>(emptySet())
    private val query = MutableStateFlow("")
    private val transient = MutableStateFlow(TransientState())

    private data class TransientState(
        val refreshing: Boolean = false,
        val loadingMore: Boolean = false,
        /** Counted rather than a flag, so overlapping actions cannot clear it early. */
        val pending: Int = 0,
        val error: String? = null,
        val status: String? = null,
        val undos: List<Undoable> = emptyList()
    ) {
        val busy: Boolean get() = pending > 0
    }

    private val accounts = container.db.accountDao().observeAll()
    private val folders = container.db.folderDao().observeAll()

    private val currentFolder: StateFlow<FolderEntity?> =
        combine(target, folders) { selected, list ->
            (selected as? MailTarget.Folder)?.let { folder -> list.firstOrNull { it.id == folder.id } }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * What the list is actually searched for. The box itself reports every
     * letter, so that the field keeps up with the typing; the database is only
     * asked once the typing stops.
     */
    private val typedQuery = query.settled(SEARCH_SETTLE_MILLIS) { it.isBlank() }

    /**
     * How much of the list is on screen at once.
     *
     * A fixed limit made the bottom of a long folder unreachable: paging older
     * mail in put it in the cache, and a query that never asked for more than
     * the first [PAGE] rows meant none of it was ever drawn.
     */
    private val visibleLimit = MutableStateFlow(PAGE)

    private val messages = combine(
        target,
        typedQuery,
        visibleLimit,
        container.settings.state.map { it.groupIntoConversations }.distinctUntilChanged()
    ) { selected, q, limit, _ -> Triple(selected, q, limit) }
        .flatMapLatest { (selected, q, limit) ->
            val threaded = container.settings.current.groupIntoConversations
            when (selected) {
                // A search is a hunt for one message, so it always shows the
                // messages themselves rather than the threads holding them.
                is MailTarget.Unified -> when {
                    q.isNotBlank() ->
                        container.db.messageDao().searchUnified(selected.type.name, q, limit)
                    threaded ->
                        container.db.messageDao().observeUnifiedThreads(selected.type.name, limit)
                    else -> container.db.messageDao().observeUnified(selected.type.name, limit)
                }
                is MailTarget.Folder -> when {
                    q.isNotBlank() -> container.db.messageDao().searchInFolder(selected.id, q, limit)
                    threaded -> container.db.messageDao().observeFolderThreads(selected.id, limit)
                    else -> container.db.messageDao().observeFolder(selected.id, limit)
                }
                is MailTarget.Search -> container.db.messageDao().searchEverywhere(q, limit)
                is MailTarget.Thread -> container.db.messageDao().observeThread(selected.id, limit)
            }
        }

    val state: StateFlow<MailUiState> = combine(
        accounts, folders, currentFolder, messages,
        combine(
            selection,
            query,
            container.syncManager.state,
            transient,
            container.settings.state
        ) { sel, q, sync, extra, settings ->
            Quad(sel, q, sync.running.isNotEmpty(), extra, settings)
        }
    ) { accountList, folderList, folder, messageList, rest ->
        MailUiState(
            accounts = accountList,
            folders = folderList,
            target = target.value,
            currentFolder = folder,
            messages = messageList,
            selection = rest.selection,
            query = rest.query,
            syncing = rest.syncing,
            refreshing = rest.extra.refreshing,
            loadingMore = rest.extra.loadingMore,
            busy = rest.extra.busy,
            error = rest.extra.error,
            status = rest.extra.status,
            undos = rest.extra.undos,
            swipeRight = rest.settings.swipeRight,
            swipeLeft = rest.settings.swipeLeft,
            swipeThreshold = rest.settings.swipeThresholdFraction
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MailUiState())

    private data class Quad(
        val selection: Set<Long>,
        val query: String,
        val syncing: Boolean,
        val extra: TransientState,
        val settings: AppSettings
    )

    init {
        // Optimistic removals report their failures here, since the screen that
        // started them may already be gone.
        viewModelScope.launch {
            container.syncManager.alerts.collect { alert ->
                transient.update { it.copy(error = alert) }
            }
        }

        // Removals wait a few seconds before they happen, and this is the offer
        // to spend them differently.
        viewModelScope.launch {
            container.syncManager.undoable.collect { offer ->
                transient.update { it.copy(undos = it.undos + offer) }
                // Each one goes when its own window closes, not when the next
                // arrives: they were started at different moments.
                launch {
                    delay(SyncManager.UNDO_WINDOW_MILLIS)
                    dismissUndo(offer.token)
                }
            }
        }

        // The message screen swipes along whatever the list is showing, which
        // only the list knows: it depends on the folder and on any search.
        viewModelScope.launch {
            state.map { ui -> ui.messages.map { it.id } }
                .distinctUntilChanged()
                .collect { container.messageOrder.publish(it) }
        }

        // Pull the bodies of unread mail down while the list is on screen, so
        // opening any of it is instant and works with no connection.
        //
        // The trigger is the folder plus the newest message rather than the
        // list of candidates: each fetched body would otherwise change the
        // candidate list and restart the run it came from.
        viewModelScope.launch {
            state.map { it.target to it.messages.firstOrNull()?.id }
                .distinctUntilChanged()
                .collectLatest { (destination, newest) ->
                    if (newest == null || !container.settings.current.preloadUnread) {
                        return@collectLatest
                    }
                    // Let the folder settle, and leave the connection to
                    // whatever the user does in the first moments after it opens.
                    delay(PREFETCH_DELAY_MILLIS)
                    val ids = when (destination) {
                        is MailTarget.Folder -> container.db.messageDao()
                            .unreadWithoutBody(destination.id, SyncManager.PREFETCH_LIMIT)
                        is MailTarget.Unified -> container.db.messageDao()
                            .unreadWithoutBodyUnified(
                                destination.type.name, SyncManager.PREFETCH_LIMIT
                            )
                        // Search results are somebody looking for one thing,
                        // not a list to read through. A conversation is already
                        // open and its bodies come down as it is read.
                        MailTarget.Search, is MailTarget.Thread -> emptyList()
                    }
                    container.syncManager.prefetchBodies(ids)
                }
        }
    }

    // ----------------------------------------------------------- navigation

    fun open(destination: MailTarget) {
        target.value = destination
        selection.value = emptySet()
        query.value = ""
        visibleLimit.value = PAGE
        if (refreshesOnOpen(destination)) refresh()
    }

    fun folderAction(folder: FolderEntity, action: FolderAction) = launchGuarded {
        when (action) {
            FolderAction.MOVE_UP -> container.syncManager.moveFolder(folder.id, -1)
            FolderAction.MOVE_DOWN -> container.syncManager.moveFolder(folder.id, 1)
            FolderAction.HIDE -> {
                container.syncManager.setFolderHidden(folder.id, true)
                if ((target.value as? MailTarget.Folder)?.id == folder.id) {
                    open(MailTarget.INBOXES)
                }
                report(text(R.string.status_hidden, folder.displayName))
            }
            FolderAction.SHOW -> container.syncManager.setFolderHidden(folder.id, false)
            FolderAction.TOGGLE_SYNC ->
                container.syncManager.setFolderSyncEnabled(folder.id, !folder.syncEnabled)
            FolderAction.TOGGLE_NOTIFY -> {
                val syncTurnedOn = container.syncManager
                    .setFolderNotify(folder.id, !folder.notify)
                report(
                    when {
                        syncTurnedOn -> text(R.string.status_notify_on_with_sync, folder.displayName)
                        !folder.notify -> text(R.string.status_notify_on, folder.displayName)
                        else -> text(R.string.status_notify_off, folder.displayName)
                    }
                )
            }
            FolderAction.MARK_READ -> {
                val count = container.syncManager.markFolderRead(folder.id)
                report(
                    if (count == 0) text(R.string.status_nothing_unread)
                    else text(R.string.status_marked_read, count)
                )
            }
            FolderAction.EMPTY -> {
                val count = container.syncManager.emptyFolder(folder.id)
                report(
                    if (count == 0) text(R.string.status_bin_already_empty)
                    else container.appContext.resources.getQuantityString(
                        R.plurals.status_bin_emptied, count, count
                    )
                )
            }
            FolderAction.RESET_ORDER -> {
                container.syncManager.resetFolderOrder(folder.accountId)
                report(text(R.string.status_order_reset))
            }
        }
    }

    fun setQuery(value: String) {
        query.value = value
        // A different set of results starts at the top again.
        visibleLimit.value = PAGE
    }

    /** Switches the list to searching everything cached, everywhere. */
    fun searchEverywhere() {
        selection.value = emptySet()
        visibleLimit.value = PAGE
        target.value = MailTarget.Search
    }

    /**
     * Asks the servers as well, for mail too old to have been synced. Every
     * folder currently on screen is searched, since a search that only covered
     * one of them would quietly miss the rest.
     */
    fun searchOnServer() = launchGuarded(showBusy = true) {
        val q = query.value.trim()
        if (q.isBlank()) return@launchGuarded
        val folders = when (val destination = target.value) {
            is MailTarget.Folder -> listOfNotNull(destination.id)
            is MailTarget.Unified -> state.value.visibleFolders
                .filter { it.effectiveType == destination.type.name && !it.isLocal }.map { it.id }
            MailTarget.Search, is MailTarget.Thread -> state.value.visibleFolders
                .filter { !it.isLocal && it.selectable }.map { it.id }
        }
        var found = 0
        folders.forEach { id ->
            runCatching { found += container.syncManager.searchOnServer(id, q) }
        }
        report(
            if (found == 0) text(R.string.status_nothing_more)
            else text(R.string.status_from_server, found)
        )
    }

    // ------------------------------------------------------------ selection

    fun toggleSelection(id: Long) {
        selection.update { if (id in it) it - id else it + id }
    }

    /** What is picked out right now, for a drag that is about to add to it. */
    fun currentSelection(): Set<Long> = selection.value

    /**
     * Picks out everything between [anchor] and [through], on top of whatever
     * [base] was already picked out when the drag began.
     *
     * Set rather than toggled, so the same drag position arriving twice — from
     * the finger and again from the list scrolling underneath it — lands on the
     * same answer. Dragging back towards the anchor gives up what was reached,
     * without disturbing anything selected before the drag started.
     */
    fun selectRange(base: Set<Long>, anchor: Long, through: Long) {
        val ids = state.value.messages.map { it.id }
        selection.value = base + rangeBetween(ids, anchor, through)
    }

    /**
     * Picks out a whole day at once, or gives it back when it is already all
     * picked out.
     *
     * A day's mail is the one grouping the list already draws, so the heading
     * over it is the obvious place to reach for all of it. Toggling means the
     * same tap undoes a day taken by mistake, and only that day: anything
     * picked out elsewhere is left where it is.
     */
    fun toggleDay(ids: List<Long>) {
        selection.update { withDayToggled(it, ids) }
    }

    fun selectAll() {
        selection.value = state.value.messages.map { it.id }.toSet()
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    // -------------------------------------------------------------- actions

    /**
     * The indicator is driven by this flag rather than by SyncManager's own
     * state: a single-folder sync never touches that, so the value would never
     * change and PullToRefreshBox would leave its spinner on screen.
     */
    fun refresh() {
        if (transient.value.refreshing) return
        transient.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                runCatching {
                    when (val destination = target.value) {
                        is MailTarget.Folder -> container.syncManager.syncFolder(destination.id)
                        // syncAll would only cover folders marked for background
                        // sync, which leaves the Sent and Trash views empty.
                        is MailTarget.Unified -> container.syncManager.syncUnified(destination.type)
                        MailTarget.Search -> searchOnServer()
                        // A conversation is read out of the cache; refreshing it
                        // means refreshing the folder its newest message is in.
                        is MailTarget.Thread -> state.value.messages.lastOrNull()
                            ?.let { container.syncManager.syncFolder(it.folderId) }
                    }
                }.onFailure { e ->
                    transient.update { it.copy(error = e.message ?: e.toString()) }
                }
            } finally {
                transient.update { it.copy(refreshing = false) }
            }
        }
    }

    /**
     * Reaches the end of the list: widens the window first, and only asks the
     * server once there is nothing cached left to show.
     */
    fun loadMore() {
        if (canWiden(state.value.messages.size, visibleLimit.value)) {
            visibleLimit.update { it + PAGE }
            return
        }
        val folder = currentFolder.value ?: return
        if (transient.value.loadingMore) return
        transient.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            runCatching { container.syncManager.loadOlder(folder.id) }
                .onFailure { e -> transient.update { it.copy(error = e.message) } }
            transient.update { it.copy(loadingMore = false) }
        }
    }

    fun markSelectionSeen(seen: Boolean) = withSelection { ids ->
        container.syncManager.setSeen(ids, seen)
    }

    fun starSelection(flagged: Boolean) = withSelection { ids ->
        container.syncManager.setFlagged(ids, flagged)
    }

    // These four hide the rows immediately and finish on the server afterwards,
    // so they neither block nor show a progress bar; a failure puts the rows back.
    // None of these four announce themselves any more: the removal is held
    // back for a moment first, and the offer to take it back is the message.
    fun archiveSelection() = withSelection(silent = true) { ids ->
        container.syncManager.archive(ids)
    }

    /**
     * Sends the selection to the bin, and empties the part of it that is
     * already there.
     *
     * The screen asks before this runs when anything is going for good; the
     * split is repeated here from the database rather than trusted from the
     * rows, so what is deleted is what is actually in the bin now.
     */
    fun trashSelection() = withSelection(silent = true) { ids ->
        val bins = state.value.binFolderIds()
        val (inBin, elsewhere) = container.db.messageDao().getAll(ids)
            .partition { it.folderId in bins }
        if (inBin.isNotEmpty()) {
            container.syncManager.deletePermanently(inBin.map { it.id })
        }
        if (elsewhere.isNotEmpty()) {
            container.syncManager.moveToTrash(elsewhere.map { it.id })
        }
    }

    fun deleteSelectionPermanently() = withSelection(silent = true) { ids ->
        container.syncManager.deletePermanently(ids)
    }

    fun moveSelection(targetFolderId: Long) = withSelection(silent = true) { ids ->
        container.syncManager.moveMessages(ids, targetFolderId)
    }

    // A copy leaves the rows in place, so unlike a move there is nothing to
    // report optimistically: the progress bar runs until the server is done.
    fun copySelection(targetFolderId: Long) = withSelection { ids ->
        container.syncManager.copyMessages(ids, targetFolderId)
        report(text(R.string.status_copied_count, ids.size))
    }

    fun downloadSelection() = withSelection { ids ->
        ids.forEach { container.syncManager.downloadRaw(it) }
        report(text(R.string.status_saved_to_device, ids.size))
    }

    /** Pulls every attachment off the selected mail and into Downloads. */
    fun saveSelectionAttachments() = withSelection { ids ->
        report(describe(container.syncManager.saveAttachmentsToDevice(ids), ids.size))
    }

    fun toggleSeen(messageId: Long, seen: Boolean) = launchGuarded {
        container.syncManager.setSeen(listOf(messageId), seen)
    }

    fun toggleStar(messageId: Long, flagged: Boolean) = launchGuarded {
        container.syncManager.setFlagged(listOf(messageId), flagged)
    }

    /**
     * Runs what a swipe asked for. The two that need an answer first —
     * choosing a folder, confirming a deletion — are left to the screen, which
     * is where the asking happens.
     */
    fun applySwipe(message: MessageSummary, action: SwipeAction) {
        when (action) {
            SwipeAction.SELECT -> toggleSelection(message.id)
            SwipeAction.TOGGLE_READ -> toggleSeen(message.id, !message.seen)
            SwipeAction.TOGGLE_STAR -> toggleStar(message.id, !message.flagged)
            SwipeAction.ARCHIVE -> removeOne(message.id) { container.syncManager.archive(it) }
            SwipeAction.TRASH -> removeOne(message.id) { container.syncManager.moveToTrash(it) }
            SwipeAction.NONE, SwipeAction.MOVE, SwipeAction.DELETE -> Unit
        }
    }

    fun deleteMessage(messageId: Long) = removeOne(messageId) {
        container.syncManager.deletePermanently(it)
    }

    fun moveMessage(messageId: Long, targetFolderId: Long) = removeOne(messageId) {
        container.syncManager.moveMessages(it, targetFolderId)
    }

    /** The row is hidden at once; the undo offer is what says so. */
    private fun removeOne(messageId: Long, block: suspend (List<Long>) -> Unit) =
        launchGuarded { block(listOf(messageId)) }

    fun clearStatus() = transient.update { it.copy(status = null, error = null) }

    fun undo(token: Long) {
        dismissUndo(token)
        launchGuarded { container.syncManager.undo(token) }
    }

    fun dismissUndo(token: Long) = transient.update { current ->
        current.copy(undos = current.undos.filterNot { it.token == token })
    }

    /** Returns true only the first time, so the screen knows when to say so. */
    fun unlockGravity(): Boolean {
        if (container.settings.current.gravityUnlocked) return false
        container.settings.update { it.copy(gravityUnlocked = true) }
        return true
    }

    // ------------------------------------------------------------ internals

    /**
     * [optimisticStatus] is reported straight away rather than after the server
     * replies, because the rows are already gone from the list by then.
     */
    private fun withSelection(
        optimisticStatus: ((List<Long>) -> String)? = null,
        silent: Boolean = false,
        block: suspend (List<Long>) -> Unit
    ) {
        val ids = selection.value.toList()
        if (ids.isEmpty()) return
        selection.value = emptySet()
        optimisticStatus?.let { report(it(ids)) }
        launchGuarded(showBusy = optimisticStatus == null && !silent) { block(ids) }
    }

    private fun launchGuarded(showBusy: Boolean = false, block: suspend () -> Unit) {
        if (showBusy) transient.update { it.copy(pending = it.pending + 1) }
        viewModelScope.launch {
            try {
                runCatching { block() }
                    .onFailure { e ->
                        transient.update { it.copy(error = e.message ?: e.toString()) }
                    }
            } finally {
                if (showBusy) {
                    transient.update { it.copy(pending = (it.pending - 1).coerceAtLeast(0)) }
                }
            }
        }
    }

    private fun report(message: String) = transient.update { it.copy(status = message) }

    private fun text(id: Int, vararg args: Any) = container.appContext.getString(id, *args)

    /** Puts an attachment save into words, in whatever language the app is in. */
    private fun describe(result: SavedAttachments, selection: Int): String =
        when (result.outcome) {
            SavedAttachments.Outcome.NOTHING_ATTACHED ->
                if (selection == 1) text(R.string.attach_none_one)
                else text(R.string.attach_none_many)
            SavedAttachments.Outcome.ALL_FAILED -> text(R.string.error_no_attachment)
            SavedAttachments.Outcome.SAVED -> if (result.partial) {
                text(
                    R.string.attach_partial,
                    result.saved, DeviceDownloads.folderLabel, result.missing
                )
            } else {
                text(R.string.attach_saved, result.saved, DeviceDownloads.folderLabel)
            }
        }

    companion object {
        /**
         * Whether the list is only showing as much as it was asked for, which
         * means the cache has more and widening the window will draw it. A list
         * shorter than its own window has run out of cached mail, and the rest
         * has to come from the server.
         */
        /**
         * Whether arriving somewhere should ask the server for it.
         *
         * Folders did and the unified views did not, which is why "All deleted
         * mails" could sit empty over a bin the server had mail in: the trash
         * is not background-synced, so nothing else was ever going to fetch it.
         * A conversation is read out of the cache, and a search has its own
         * button for reaching the server.
         */
        fun refreshesOnOpen(target: MailTarget): Boolean =
            target is MailTarget.Folder || target is MailTarget.Unified

        fun canWiden(shown: Int, window: Int): Boolean = shown >= window

        /**
         * A day's worth of ids added to [current], or taken out of it when
         * every one of them is in there already.
         *
         * A day half picked out is treated as not picked out: the tap that
         * follows a few individual ones should finish the job rather than
         * undo them.
         */
        fun withDayToggled(current: Set<Long>, day: List<Long>): Set<Long> =
            if (day.isNotEmpty() && current.containsAll(day)) current - day.toSet()
            else current + day

        private const val PAGE = 300

        /** How long the typing has to stop before the database is asked. */
        private const val SEARCH_SETTLE_MILLIS = 150L
        private const val PREFETCH_DELAY_MILLIS = 1_500L
    }
}
