package de.uwumail.ui.folders

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uwumail.R
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
    val synced = stringResource(R.string.folder_synced)
    val hidden = stringResource(R.string.folder_hidden)
    val notifies = stringResource(R.string.folder_notifies)

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            Column {
            TopAppBar(
                title = { Text(stringResource(R.string.folders_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.busy) {
                        Icon(Icons.Default.Sync, stringResource(R.string.refresh_from_server))
                    }
                }
            )
            // Creating or deleting a folder is a server round trip; show that
            // something is happening instead of leaving the list looking frozen.
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { if (!state.busy) fabMenu = true },
                    icon = {
                        if (state.busy) CircularProgressIndicator(Modifier.size(20.dp))
                        else Icon(Icons.Default.Add, null)
                    },
                    text = { Text(if (state.busy) stringResource(R.string.working) else stringResource(R.string.new_folder)) }
                )
                DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.on_the_server)) },
                        leadingIcon = { Icon(Icons.Default.CloudQueue, null) },
                        onClick = { fabMenu = false; createRemote = true }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.on_device_only)) },
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
            itemsIndexed(state.folders, key = { _, folder -> folder.id }) { index, folder ->
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
                                append(if (folder.isLocal) stringResource(R.string.device_folder) else folder.path)
                                append(" · ${folder.totalCount} messages")
                                if (folder.unreadCount > 0) append(", ${folder.unreadCount} unread")
                                if (folder.syncEnabled && !folder.isLocal) append(synced)
                                if (folder.notify && !folder.isLocal) append(notifies)
                                if (folder.hidden) append(hidden)
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    trailingContent = {
                        FolderActions(
                            folder = folder,
                            canMoveUp = index > 0,
                            canMoveDown = index < state.folders.lastIndex,
                            onMove = { viewModel.move(folder, it) },
                            onToggleSync = { viewModel.setSyncEnabled(folder, it) },
                            onToggleNotify = { viewModel.setNotify(folder, it) },
                            onToggleHidden = { viewModel.setHidden(folder, it) },
                            onResetOrder = viewModel::resetOrder,
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
            title = stringResource(R.string.new_imap_folder),
            label = stringResource(R.string.folder_path),
            supportingText = "Use ${state.delimiter} to nest, e.g. Projects${state.delimiter}CI",
            onConfirm = viewModel::createRemoteFolder,
            onDismiss = { createRemote = false }
        )
    }
    if (createLocal) {
        TextPromptDialog(
            title = stringResource(R.string.new_device_folder),
            label = stringResource(R.string.name),
            supportingText = stringResource(R.string.device_folder_hint),
            onConfirm = viewModel::createLocalFolder,
            onDismiss = { createLocal = false }
        )
    }
    renaming?.let { folder ->
        TextPromptDialog(
            title = stringResource(R.string.rename_folder),
            label = stringResource(R.string.new_path),
            initial = if (folder.isLocal) folder.displayName else folder.path,
            confirmLabel = stringResource(R.string.rename),
            onConfirm = { viewModel.rename(folder, it) },
            onDismiss = { renaming = null }
        )
    }
    deleting?.let { folder ->
        ConfirmDialog(
            title = "Delete \"${folder.displayName}\"?",
            message = if (folder.isLocal) {
                stringResource(R.string.folder_delete_local)
            } else {
                stringResource(R.string.folder_delete_remote)
            },
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = { viewModel.delete(folder) },
            onDismiss = { deleting = null }
        )
    }
}

@Composable
private fun FolderActions(
    folder: FolderEntity,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onToggleSync: (Boolean) -> Unit,
    onToggleNotify: (Boolean) -> Unit,
    onToggleHidden: (Boolean) -> Unit,
    onResetOrder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Default.DriveFileRenameOutline, stringResource(R.string.folder_actions))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.move_up)) },
                enabled = canMoveUp,
                leadingIcon = { Icon(Icons.Default.ArrowUpward, null) },
                onClick = { menu = false; onMove(-1) }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.move_down)) },
                enabled = canMoveDown,
                leadingIcon = { Icon(Icons.Default.ArrowDownward, null) },
                onClick = { menu = false; onMove(1) }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.reset_order)) },
                onClick = { menu = false; onResetOrder() }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (folder.hidden) stringResource(R.string.show_in_list) else stringResource(R.string.hide_from_list)) },
                leadingIcon = {
                    Icon(
                        if (folder.hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        null
                    )
                },
                onClick = { menu = false; onToggleHidden(!folder.hidden) }
            )
            if (!folder.isLocal) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sync_automatically)) },
                    trailingIcon = {
                        Switch(checked = folder.syncEnabled, onCheckedChange = null)
                    },
                    onClick = { menu = false; onToggleSync(!folder.syncEnabled) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.notify_of_new_mail)) },
                    trailingIcon = {
                        Switch(checked = folder.notify, onCheckedChange = null)
                    },
                    onClick = { menu = false; onToggleNotify(!folder.notify) }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename)) },
                onClick = { menu = false; onRename() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = { menu = false; onDelete() }
            )
        }
    }
}
