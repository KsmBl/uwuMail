package de.uwumail.ui.folders

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.uwumail.data.db.FolderEntity
import de.uwumail.ui.common.ConfirmDialog
import de.uwumail.ui.common.TextPromptDialog
import de.uwumail.ui.containerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(accountId: Long, onBack: () -> Unit) {
    val viewModel = containerViewModel(key = "folders-$accountId") {
        FoldersViewModel(it, accountId)
    }
    val state by viewModel.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    var createRemote by remember { mutableStateOf(false) }
    var createLocal by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<FolderEntity?>(null) }
    var deleting by remember { mutableStateOf<FolderEntity?>(null) }
    var fabMenu by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("Folders") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.busy) {
                        Icon(Icons.Default.Sync, "Refresh from server")
                    }
                }
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { fabMenu = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("New folder") }
                )
                DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("On the server (IMAP)") },
                        leadingIcon = { Icon(Icons.Default.CloudQueue, null) },
                        onClick = { fabMenu = false; createRemote = true }
                    )
                    DropdownMenuItem(
                        text = { Text("On this device only") },
                        leadingIcon = { Icon(Icons.Default.PhoneAndroid, null) },
                        onClick = { fabMenu = false; createLocal = true }
                    )
                }
            }
        }
    ) { padding ->
        if (state.busy && state.folders.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(state.folders, key = { it.id }) { folder ->
                ListItem(
                    leadingContent = {
                        Icon(
                            if (folder.isLocal) Icons.Default.PhoneAndroid else Icons.Default.CloudQueue,
                            contentDescription = null
                        )
                    },
                    headlineContent = { Text(folder.displayName) },
                    supportingContent = {
                        Text(
                            buildString {
                                append(if (folder.isLocal) "device folder" else folder.path)
                                append(" · ${folder.totalCount} messages")
                                if (folder.unreadCount > 0) append(", ${folder.unreadCount} unread")
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    trailingContent = {
                        FolderActions(
                            folder = folder,
                            onToggleSync = { viewModel.setSyncEnabled(folder, it) },
                            onRename = { renaming = folder },
                            onDelete = { deleting = folder }
                        )
                    }
                )
                HorizontalDivider()
            }
        }
    }

    if (createRemote) {
        TextPromptDialog(
            title = "New IMAP folder",
            label = "Folder path",
            supportingText = "Use ${state.delimiter} to nest, e.g. Projects${state.delimiter}CI",
            onConfirm = viewModel::createRemoteFolder,
            onDismiss = { createRemote = false }
        )
    }
    if (createLocal) {
        TextPromptDialog(
            title = "New device folder",
            label = "Name",
            supportingText = "Messages moved here are downloaded and removed from the server",
            onConfirm = viewModel::createLocalFolder,
            onDismiss = { createLocal = false }
        )
    }
    renaming?.let { folder ->
        TextPromptDialog(
            title = "Rename folder",
            label = "New path",
            initial = if (folder.isLocal) folder.displayName else folder.path,
            confirmLabel = "Rename",
            onConfirm = { viewModel.rename(folder, it) },
            onDismiss = { renaming = null }
        )
    }
    deleting?.let { folder ->
        ConfirmDialog(
            title = "Delete \"${folder.displayName}\"?",
            message = if (folder.isLocal) {
                "The downloaded messages in this device folder are deleted permanently."
            } else {
                "The folder and everything in it is deleted on the server. This cannot be undone."
            },
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { viewModel.delete(folder) },
            onDismiss = { deleting = null }
        )
    }
}

@Composable
private fun FolderActions(
    folder: FolderEntity,
    onToggleSync: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Default.DriveFileRenameOutline, "Folder actions")
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (!folder.isLocal) {
                DropdownMenuItem(
                    text = { Text("Sync automatically") },
                    trailingIcon = {
                        Switch(checked = folder.syncEnabled, onCheckedChange = null)
                    },
                    onClick = { menu = false; onToggleSync(!folder.syncEnabled) }
                )
            }
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = { menu = false; onRename() }
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = { menu = false; onDelete() }
            )
        }
    }
}
