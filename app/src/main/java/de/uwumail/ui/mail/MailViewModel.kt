package de.uwumail.ui.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.FolderEntity
import de.uwumail.data.db.MessageSummary
import de.uwumail.di.AppContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MailUiState(
    val accounts: List<AccountEntity> = emptyList(),
    val folders: List<FolderEntity> = emptyList(),
    val currentFolder: FolderEntity? = null,
    val messages: List<MessageSummary> = emptyList(),
    val selection: Set<Long> = emptySet(),
    val query: String = "",
    val syncing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val status: String? = null
) {
    val inSelectionMode: Boolean get() = selection.isNotEmpty()
    val title: String get() = currentFolder?.displayName ?: "All inboxes"
    /** Folders that can receive a move: every folder except the one we're in. */
    fun moveTargets(): List<FolderEntity> {
        val accountId = currentFolder?.accountId
        return folders
            .filter { it.selectable }
            .filter { it.id != currentFolder?.id }
            .filter { accountId == null || it.accountId == accountId }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MailViewModel(private val container: AppContainer) : ViewModel() {

    private val selectedFolderId = MutableStateFlow<Long?>(null)
    private val selection = MutableStateFlow<Set<Long>>(emptySet())
    private val query = MutableStateFlow("")
    private val transient = MutableStateFlow(TransientState())

    private data class TransientState(
        val loadingMore: Boolean = false,
        val error: String? = null,
        val status: String? = null
    )

    private val accounts = container.db.accountDao().observeAll()
    private val folders = container.db.folderDao().observeAll()

    private val currentFolder: StateFlow<FolderEntity?> =
        combine(selectedFolderId, folders) { id, list -> list.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val messages = combine(selectedFolderId, query) { id, q -> id to q }
        .flatMapLatest { (id, q) ->
            when {
                id == null -> container.db.messageDao().observeUnifiedInbox(PAGE)
                q.isBlank() -> container.db.messageDao().observeFolder(id, PAGE)
                else -> container.db.messageDao().searchInFolder(id, q, PAGE)
            }
        }

    val state: StateFlow<MailUiState> = combine(
        accounts, folders, currentFolder, messages,
        combine(selection, query, container.syncManager.state, transient) { sel, q, sync, extra ->
            Quad(sel, q, sync.running.isNotEmpty(), extra)
        }
    ) { accountList, folderList, folder, messageList, rest ->
        MailUiState(
            accounts = accountList,
            folders = folderList,
            currentFolder = folder,
            messages = messageList,
            selection = rest.selection,
            query = rest.query,
            syncing = rest.syncing,
            loadingMore = rest.extra.loadingMore,
            error = rest.extra.error,
            status = rest.extra.status
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MailUiState())

    private data class Quad(
        val selection: Set<Long>,
        val query: String,
        val syncing: Boolean,
        val extra: TransientState
    )

    // ----------------------------------------------------------- navigation

    fun openFolder(folderId: Long?) {
        selectedFolderId.value = folderId
        selection.value = emptySet()
        query.value = ""
        folderId?.let { refresh() }
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

    fun refresh() = launchGuarded {
        val folder = currentFolder.value
        if (folder != null) {
            container.syncManager.syncFolder(folder.id)
        } else {
            container.syncManager.syncAll()
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

    fun archiveSelection() = withSelection { ids ->
        container.syncManager.archive(ids)
        report("${ids.size} archived")
    }

    fun trashSelection() = withSelection { ids ->
        container.syncManager.moveToTrash(ids)
        report("${ids.size} moved to trash")
    }

    fun deleteSelectionPermanently() = withSelection { ids ->
        container.syncManager.deletePermanently(ids)
        report("${ids.size} deleted")
    }

    fun moveSelection(targetFolderId: Long) = withSelection { ids ->
        container.syncManager.moveMessages(ids, targetFolderId)
        report("${ids.size} moved")
    }

    fun downloadSelection() = withSelection { ids ->
        ids.forEach { container.syncManager.downloadRaw(it) }
        report("Saved ${ids.size} message${if (ids.size == 1) "" else "s"} to device")
    }

    fun toggleSeen(messageId: Long, seen: Boolean) = launchGuarded {
        container.syncManager.setSeen(listOf(messageId), seen)
    }

    fun toggleStar(messageId: Long, flagged: Boolean) = launchGuarded {
        container.syncManager.setFlagged(listOf(messageId), flagged)
    }

    fun clearStatus() = transient.update { it.copy(status = null, error = null) }

    // ------------------------------------------------------------ internals

    private fun withSelection(block: suspend (List<Long>) -> Unit) {
        val ids = selection.value.toList()
        if (ids.isEmpty()) return
        selection.value = emptySet()
        launchGuarded { block(ids) }
    }

    private fun launchGuarded(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { e -> transient.update { it.copy(error = e.message ?: e.toString()) } }
        }
    }

    private fun report(message: String) = transient.update { it.copy(status = message) }

    companion object {
        private const val PAGE = 300
    }
}
