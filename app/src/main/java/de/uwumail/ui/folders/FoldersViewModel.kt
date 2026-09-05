package de.uwumail.ui.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
        guarded("Folder list updated") { container.syncManager.refreshFolders(accountId) }

    fun createRemoteFolder(path: String) =
        guarded("Created \"$path\"") { container.syncManager.createRemoteFolder(accountId, path) }

    fun createLocalFolder(name: String) =
        guarded("Created device folder \"$name\"") {
            container.syncManager.createLocalFolder(accountId, name)
        }

    fun rename(folder: FolderEntity, newPath: String) =
        guarded("Renamed to \"$newPath\"") {
            container.syncManager.renameRemoteFolder(folder.id, newPath)
        }

    fun delete(folder: FolderEntity) =
        guarded("Deleted \"${folder.displayName}\"") {
            container.syncManager.deleteRemoteFolder(folder.id)
        }

    fun setSyncEnabled(folder: FolderEntity, enabled: Boolean) {
        viewModelScope.launch {
            container.db.folderDao().update(folder.copy(syncEnabled = enabled))
        }
    }

    fun clearMessage() = transient.update { it.copy(message = null) }

    private fun guarded(success: String, block: suspend () -> Unit) {
        transient.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = runCatching { block() }
            transient.update {
                it.copy(
                    busy = false,
                    message = result.exceptionOrNull()?.message?.let { e -> "Failed: $e" } ?: success
                )
            }
        }
    }
}
