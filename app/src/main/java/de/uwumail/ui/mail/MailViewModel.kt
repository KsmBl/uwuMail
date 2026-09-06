package de.uwumail.ui.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.uwumail.core.DeviceDownloads
import de.uwumail.core.FolderType
import de.uwumail.core.SwipeAction
import de.uwumail.data.settings.AppSettings
import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.FolderEntity
import de.uwumail.data.db.MessageSummary
import de.uwumail.di.AppContainer
import de.uwumail.sync.SyncManager
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
enum class FolderAction { MOVE_UP, MOVE_DOWN, HIDE, SHOW, TOGGLE_SYNC, MARK_READ, RESET_ORDER }

/** What the message list is currently showing. */
sealed interface MailTarget {
    /** Every folder of one role, across all accounts. */
    data class Unified(val type: FolderType) : MailTarget
    data class Folder(val id: Long) : MailTarget

    companion object {
        val INBOXES = Unified(FolderType.INBOX)
        val OUTBOXES = Unified(FolderType.SENT)
        val DELETED = Unified(FolderType.TRASH)

        /** The unified rows shown at the top of the drawer, in order. */
        val UNIFIED = listOf(
            INBOXES to "All inboxes",
            OUTBOXES to "All outboxes",
            DELETED to "All deleted mails"
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
    /** What a swipe across a row does, per direction. */
    val swipeRight: SwipeAction = SwipeAction.ARCHIVE,
    val swipeLeft: SwipeAction = SwipeAction.TRASH
) {
    val inSelectionMode: Boolean get() = selection.isNotEmpty()

    val title: String
        get() = currentFolder?.displayName
            ?: MailTarget.UNIFIED.firstOrNull { it.first == target }?.second
            ?: "Mail"

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
        val status: String? = null
    ) {
        val busy: Boolean get() = pending > 0
    }

    private val accounts = container.db.accountDao().observeAll()
    private val folders = container.db.folderDao().observeAll()

    private val currentFolder: StateFlow<FolderEntity?> =
        combine(target, folders) { selected, list ->
            (selected as? MailTarget.Folder)?.let { folder -> list.firstOrNull { it.id == folder.id } }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val messages = combine(target, query) { selected, q -> selected to q }
        .flatMapLatest { (selected, q) ->
            when (selected) {
                is MailTarget.Unified ->
                    container.db.messageDao().observeUnified(selected.type.name, PAGE)
                is MailTarget.Folder ->
                    if (q.isBlank()) container.db.messageDao().observeFolder(selected.id, PAGE)
                    else container.db.messageDao().searchInFolder(selected.id, q, PAGE)
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
            swipeRight = rest.settings.swipeRight,
            swipeLeft = rest.settings.swipeLeft
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
        if (destination is MailTarget.Folder) refresh()
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
                report("\"${folder.displayName}\" hidden on this device")
            }
            FolderAction.SHOW -> container.syncManager.setFolderHidden(folder.id, false)
            FolderAction.TOGGLE_SYNC ->
                container.syncManager.setFolderSyncEnabled(folder.id, !folder.syncEnabled)
            FolderAction.MARK_READ -> {
                val count = container.syncManager.markFolderRead(folder.id)
                report(if (count == 0) "Nothing unread" else "$count marked as read")
            }
            FolderAction.RESET_ORDER -> {
                container.syncManager.resetFolderOrder(folder.accountId)
                report("Folder order reset")
            }
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    // ------------------------------------------------------------ selection

    fun toggleSelection(id: Long) {
        selection.update { if (id in it) it - id else it + id }
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
                    }
                }.onFailure { e ->
                    transient.update { it.copy(error = e.message ?: e.toString()) }
                }
            } finally {
                transient.update { it.copy(refreshing = false) }
            }
        }
    }

    fun loadMore() {
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
    fun archiveSelection() = withSelection(
        optimisticStatus = { "${it.size} archived" }
    ) { ids -> container.syncManager.archive(ids) }

    fun trashSelection() = withSelection(
        optimisticStatus = { "${it.size} moved to trash" }
    ) { ids -> container.syncManager.moveToTrash(ids) }

    fun deleteSelectionPermanently() = withSelection(
        optimisticStatus = { "${it.size} deleted" }
    ) { ids -> container.syncManager.deletePermanently(ids) }

    fun moveSelection(targetFolderId: Long) = withSelection(
        optimisticStatus = { "${it.size} moved" }
    ) { ids -> container.syncManager.moveMessages(ids, targetFolderId) }

    // A copy leaves the rows in place, so unlike a move there is nothing to
    // report optimistically: the progress bar runs until the server is done.
    fun copySelection(targetFolderId: Long) = withSelection { ids ->
        container.syncManager.copyMessages(ids, targetFolderId)
        report("${ids.size} copied")
    }

    fun downloadSelection() = withSelection { ids ->
        ids.forEach { container.syncManager.downloadRaw(it) }
        report("Saved ${ids.size} message${if (ids.size == 1) "" else "s"} to device")
    }

    /** Pulls every attachment off the selected mail and into Downloads. */
    fun saveSelectionAttachments() = withSelection { ids ->
        val result = container.syncManager.saveAttachmentsToDevice(ids)
        report(result.summary(ids.size, DeviceDownloads.folderLabel))
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
            SwipeAction.TOGGLE_READ -> toggleSeen(message.id, !message.seen)
            SwipeAction.TOGGLE_STAR -> toggleStar(message.id, !message.flagged)
            SwipeAction.ARCHIVE -> removeOne(message.id, "Archived") {
                container.syncManager.archive(it)
            }
            SwipeAction.TRASH -> removeOne(message.id, "Moved to trash") {
                container.syncManager.moveToTrash(it)
            }
            SwipeAction.NONE, SwipeAction.MOVE, SwipeAction.DELETE -> Unit
        }
    }

    fun deleteMessage(messageId: Long) = removeOne(messageId, "Deleted") {
        container.syncManager.deletePermanently(it)
    }

    fun moveMessage(messageId: Long, targetFolderId: Long) = removeOne(messageId, "Moved") {
        container.syncManager.moveMessages(it, targetFolderId)
    }

    /**
     * The row is hidden the moment this starts, so the outcome is reported at
     * once rather than after the server has answered — by then it is gone.
     */
    private fun removeOne(messageId: Long, done: String, block: suspend (List<Long>) -> Unit) {
        report(done)
        launchGuarded { block(listOf(messageId)) }
    }

    fun clearStatus() = transient.update { it.copy(status = null, error = null) }

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
        block: suspend (List<Long>) -> Unit
    ) {
        val ids = selection.value.toList()
        if (ids.isEmpty()) return
        selection.value = emptySet()
        optimisticStatus?.let { report(it(ids)) }
        launchGuarded(showBusy = optimisticStatus == null) { block(ids) }
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

    companion object {
        private const val PAGE = 300
        private const val PREFETCH_DELAY_MILLIS = 1_500L
    }
}
