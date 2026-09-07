package de.uwumail.ui.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.uwumail.R
import de.uwumail.data.db.FolderEntity
import de.uwumail.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FoldersUiState(
    val folders: List<FolderEntity> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null
) {
    /** Hierarchy separator the server uses, needed when composing nested paths. */
    val delimiter: String get() = folders.firstOrNull { !it.isLocal }?.delimiter ?: "/"
}

class FoldersViewModel(
    private val container: AppContainer,
    private val accountId: Long
) : ViewModel() {

    private val transient = MutableStateFlow(FoldersUiState())

    val state: StateFlow<FoldersUiState> = combine(
        container.db.folderDao().observeForAccount(accountId),
        transient
    ) { folders, extra -> extra.copy(folders = folders) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoldersUiState())

    fun refresh() =
        guarded(container.appContext.getString(R.string.status_folders_updated)) { container.syncManager.refreshFolders(accountId) }

    fun createRemoteFolder(path: String) =
        guarded(text(R.string.status_folder_created, path)) {
            container.syncManager.createRemoteFolder(accountId, path)
        }

    fun createLocalFolder(name: String) =
        guarded(text(R.string.status_device_folder_created, name)) {
            container.syncManager.createLocalFolder(accountId, name)
        }

    fun rename(folder: FolderEntity, newPath: String) =
        guarded(text(R.string.status_folder_renamed, newPath)) {
            container.syncManager.renameRemoteFolder(folder.id, newPath)
        }

    fun delete(folder: FolderEntity) =
        guarded(text(R.string.status_folder_deleted, folder.displayName)) {
            container.syncManager.deleteRemoteFolder(folder.id)
        }

    /**
     * Notifications for one folder. Switching them on switches background
     * checking on with them, since a folder nobody checks has no new mail to
     * announce; the message says so when that happened.
     */
    fun setNotify(folder: FolderEntity, notify: Boolean) {
        viewModelScope.launch {
            val syncTurnedOn = runCatching {
                container.syncManager.setFolderNotify(folder.id, notify)
            }.getOrDefault(false)
            transient.update {
                it.copy(
                    message = when {
                        syncTurnedOn ->
                            text(R.string.status_notify_on_with_sync, folder.displayName)
                        notify -> text(R.string.status_notify_on, folder.displayName)
                        else -> text(R.string.status_notify_off, folder.displayName)
                    }
                )
            }
        }
    }

    fun setSyncEnabled(folder: FolderEntity, enabled: Boolean) {
        viewModelScope.launch {
            container.db.folderDao().update(folder.copy(syncEnabled = enabled))
        }
    }

    /** The folder manager lists hidden folders too, so this is the way back. */
    fun setHidden(folder: FolderEntity, hidden: Boolean) {
        viewModelScope.launch {
            container.syncManager.setFolderHidden(folder.id, hidden)
            transient.update {
                it.copy(
                    message = if (hidden) "\"${folder.displayName}\" hidden from the folder list"
                    else "\"${folder.displayName}\" shown again"
                )
            }
        }
    }

    fun move(folder: FolderEntity, delta: Int) {
        viewModelScope.launch { container.syncManager.moveFolder(folder.id, delta) }
    }

    fun resetOrder() {
        viewModelScope.launch {
            container.syncManager.resetFolderOrder(accountId)
            transient.update { it.copy(message = container.appContext.getString(R.string.status_order_reset)) }
        }
    }

    fun clearMessage() = transient.update { it.copy(message = null) }

    private fun text(id: Int, vararg args: Any) = container.appContext.getString(id, *args)

    private fun guarded(success: String, block: suspend () -> Unit) {
        transient.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = runCatching { block() }
            transient.update {
                it.copy(
                    busy = false,
                    message = result.exceptionOrNull()?.message
                        ?.let { e -> text(R.string.error_failed, e) } ?: success
                )
            }
        }
    }
}
