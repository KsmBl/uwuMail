package de.uwumail.ui.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.uwumail.R
import de.uwumail.data.db.FolderEntity
import de.uwumail.data.db.MessageSummary
import de.uwumail.ui.common.EmptyState
import de.uwumail.ui.common.FolderPickerSheet
import de.uwumail.ui.common.SectionHeader
import de.uwumail.ui.common.formatListDate
import de.uwumail.ui.containerViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailScreen(
    onOpenMessage: (Long) -> Unit,
    onCompose: (Long?) -> Unit,
    onManageRules: () -> Unit,
    onManageFolders: (Long) -> Unit,
    onManageAccounts: () -> Unit,
    onSettings: () -> Unit,
    onCreateRuleFrom: (List<Long>) -> Unit
) {
    val viewModel = containerViewModel { MailViewModel(it) }
    val state by viewModel.state.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var showMovePicker by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.status, state.error) {
        val message = state.error?.let { "Error: $it" } ?: state.status
        if (message != null) {
            snackbarHost.showSnackbar(message)
            viewModel.clearStatus()
        }
    }

    // Whether the list is parked at the very top. Recomputed only when a user
    // scroll settles, so a row arriving above the viewport cannot flip it: the
    // LazyColumn keeps the anchored row in place and shifts the index instead.
    var pinnedToTop by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    pinnedToTop = listState.firstVisibleItemIndex == 0 &&
                        listState.firstVisibleItemScrollOffset == 0
                }
            }
    }

    // New mail lands above whatever is on screen. If the user was already at the
    // top, follow it up so the new message is visible; if they had scrolled down,
    // leave their place alone.
    LaunchedEffect(state.messages.firstOrNull()?.id) {
        if (pinnedToTop &&
            state.messages.isNotEmpty() &&
            !state.inSelectionMode &&
            // Never yank the list out from under an in-progress fling.
            !listState.isScrollInProgress
        ) {
            listState.animateScrollToItem(0)
        }
    }

    // The list state outlives a folder change, so without this a new folder
    // opens at the previous one's scroll offset.
    LaunchedEffect(state.target) {
        pinnedToTop = true
        listState.scrollToItem(0)
    }

    // Pull the next page in once the user nears the end of the cached list.
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            state.messages.isNotEmpty() && last >= state.messages.size - 5
        }
    }
    LaunchedEffect(nearEnd) {
        if (nearEnd && state.currentFolder != null) viewModel.loadMore()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MailDrawer(
                state = state,
                onOpen = {
                    viewModel.open(it)
                    scope.launch { drawerState.close() }
                },
                onFolderAction = viewModel::folderAction,
                onManageRules = { scope.launch { drawerState.close() }; onManageRules() },
                onManageFolders = { scope.launch { drawerState.close() }; onManageFolders(it) },
                onManageAccounts = { scope.launch { drawerState.close() }; onManageAccounts() },
                onSettings = { scope.launch { drawerState.close() }; onSettings() }
            )
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHost) },
            topBar = {
                Column {
                if (state.inSelectionMode) {
                    SelectionAppBar(
                        count = state.selection.size,
                        onClear = viewModel::clearSelection,
                        onSelectAll = viewModel::selectAll,
                        onMarkRead = { viewModel.markSelectionSeen(true) },
                        onMarkUnread = { viewModel.markSelectionSeen(false) },
                        onStar = { viewModel.starSelection(true) },
                        onArchive = viewModel::archiveSelection,
                        onTrash = viewModel::trashSelection,
                        onDeleteForever = viewModel::deleteSelectionPermanently,
                        onMove = { showMovePicker = true },
                        onDownload = viewModel::downloadSelection,
                        onCreateRule = {
                            val ids = state.selection.toList()
                            viewModel.clearSelection()
                            onCreateRuleFrom(ids)
                        }
                    )
                } else {
                    TopAppBar(
                        title = {
                            if (showSearch) {
                                OutlinedTextField(
                                    value = state.query,
                                    onValueChange = viewModel::setQuery,
                                    placeholder = { Text("Search this folder") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                Column {
                                    Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    state.currentFolder?.let { folder ->
                                        val account = state.accounts.firstOrNull { it.id == folder.accountId }
                                        if (account != null) {
                                            Text(
                                                account.email,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Folders")
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                showSearch = !showSearch
                                if (!showSearch) viewModel.setQuery("")
                            }) {
                                Icon(
                                    if (showSearch) Icons.Default.Close else Icons.Default.Search,
                                    contentDescription = "Search"
                                )
                            }
                            IconButton(onClick = viewModel::refresh) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                            }
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Default.Rule, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Rules") },
                                    onClick = { overflowOpen = false; onManageRules() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Manage folders") },
                                    onClick = {
                                        overflowOpen = false
                                        state.currentFolder?.accountId
                                            ?.let(onManageFolders)
                                            ?: state.accounts.firstOrNull()?.let { onManageFolders(it.id) }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Accounts") },
                                    onClick = { overflowOpen = false; onManageAccounts() }
                                )
                            }
                        }
                    )
                }
                // A thin bar rather than a blocking spinner: the list stays usable
                // while a move or delete is in flight.
                if (state.busy || state.syncing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                }
            },
            floatingActionButton = {
                if (!state.inSelectionMode) {
                    ExtendedFloatingActionButton(
                        onClick = { onCompose(state.currentFolder?.accountId) },
                        icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        text = { Text("Compose") }
                    )
                }
            }
        ) { padding ->
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.padding(padding).fillMaxSize()
            ) {
                when {
                    // Both empty states live inside a LazyColumn on purpose:
                    // PullToRefreshBox detects the pull through nested scroll, so a
                    // plain Column here would make the gesture impossible exactly
                    // when it is needed most.
                    state.accounts.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Box(
                                Modifier.fillParentMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                EmptyState(
                                    title = "No accounts yet",
                                    subtitle = "Add a mail account to get started."
                                )
                            }
                        }
                    }
                    state.messages.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Box(
                                Modifier.fillParentMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                EmptyState(
                                    title = if (state.query.isBlank()) "Nothing here"
                                    else "No matches",
                                    subtitle = if (state.query.isBlank()) "Pull down to sync."
                                    else null
                                )
                            }
                        }
                    }
                    else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(state.messages, key = { it.id }) { message ->
                            MessageRow(
                                message = message,
                                selected = message.id in state.selection,
                                selectionMode = state.inSelectionMode,
                                accountColor = state.accounts
                                    .firstOrNull { it.id == message.accountId }?.color,
                                onClick = {
                                    if (state.inSelectionMode) viewModel.toggleSelection(message.id)
                                    else onOpenMessage(message.id)
                                },
                                onLongClick = { viewModel.toggleSelection(message.id) },
                                onStar = { viewModel.toggleStar(message.id, !message.flagged) }
                            )
                            HorizontalDivider(thickness = 0.5.dp)
                        }
                        if (state.loadingMore) {
                            item {
                                Box(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) { CircularProgressIndicator(Modifier.size(24.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMovePicker) {
        FolderPickerSheet(
            folders = state.moveTargets(),
            accounts = state.accounts,
            preferredAccountId = state.currentFolder?.accountId,
            onPick = {
                showMovePicker = false
                viewModel.moveSelection(it.id)
            },
            onDismiss = { showMovePicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionAppBar(
    count: Int,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onStar: () -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit,
    onDeleteForever: () -> Unit,
    onMove: () -> Unit,
    onDownload: () -> Unit,
    onCreateRule: () -> Unit
) {
    var overflow by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = {
            IconButton(onClick = onClear) { Icon(Icons.Default.Close, "Clear selection") }
        },
        actions = {
            IconButton(onClick = onArchive) { Icon(Icons.Default.Archive, "Archive") }
            IconButton(onClick = onTrash) { Icon(Icons.Default.Delete, "Trash") }
            IconButton(onClick = onMove) { Icon(Icons.Default.DriveFileMove, "Move") }
            IconButton(onClick = onCreateRule) {
                Icon(Icons.AutoMirrored.Filled.Label, "Create rule from selection")
            }
            IconButton(onClick = { overflow = true }) { Icon(Icons.Default.Rule, "More") }
            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                DropdownMenuItem(
                    text = { Text("Mark as read") },
                    leadingIcon = { Icon(Icons.Default.MarkEmailRead, null) },
                    onClick = { overflow = false; onMarkRead() }
                )
                DropdownMenuItem(
                    text = { Text("Mark as unread") },
                    leadingIcon = { Icon(Icons.Default.MarkEmailUnread, null) },
                    onClick = { overflow = false; onMarkUnread() }
                )
                DropdownMenuItem(
                    text = { Text("Star") },
                    leadingIcon = { Icon(Icons.Default.Star, null) },
                    onClick = { overflow = false; onStar() }
                )
                DropdownMenuItem(
                    text = { Text("Download to device") },
                    leadingIcon = { Icon(Icons.Default.Download, null) },
                    onClick = { overflow = false; onDownload() }
                )
                DropdownMenuItem(
                    text = { Text("Select all") },
                    leadingIcon = { Icon(Icons.Default.Check, null) },
                    onClick = { overflow = false; onSelectAll() }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text("Delete permanently", color = MaterialTheme.colorScheme.error)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                    },
                    onClick = { overflow = false; onDeleteForever() }
                )
            }
        }
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: MessageSummary,
    selected: Boolean,
    selectionMode: Boolean,
    accountColor: Int?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onStar: () -> Unit
) {
    val background = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        // A blocklisted sender is called out in red rather than hidden, so the
        // mail is still there to look at and the match can be judged.
        message.spam -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        !message.seen -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        else -> Color.Transparent
    }
    val senderColor =
        if (message.spam) MaterialTheme.colorScheme.error else Color.Unspecified
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    when {
                        selected -> MaterialTheme.colorScheme.primary
                        message.spam -> MaterialTheme.colorScheme.error
                        else -> accountColor?.let { Color(it) }
                            ?: MaterialTheme.colorScheme.primaryContainer
                    }
                )
                .clickable(onClick = onLongClick),
            contentAlignment = Alignment.Center
        ) {
            if (selected || selectionMode) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else {
                Text(
                    (message.fromName?.takeIf { it.isNotBlank() } ?: message.fromAddress ?: "?")
                        .trim().first().uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (message.spam) {
                    Icon(
                        Icons.Default.Block,
                        contentDescription = "Sender is on a spam list",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    message.fromName?.takeIf { it.isNotBlank() }
                        ?: message.fromAddress.orEmpty().ifBlank { "(unknown sender)" },
                    style = MaterialTheme.typography.titleSmall,
                    color = senderColor,
                    fontWeight = if (message.seen) FontWeight.Normal else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (message.isLocal) {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = "Stored on device",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (message.hasAttachments) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = "Has attachments",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    formatListDate(message.receivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                message.subject.ifBlank { "(no subject)" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (message.seen) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (message.preview.isNotBlank()) {
                Text(
                    message.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onStar, modifier = Modifier.size(32.dp)) {
            Icon(
                if (message.flagged) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = "Star",
                tint = if (message.flagged) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun MailDrawer(
    state: MailUiState,
    onOpen: (MailTarget) -> Unit,
    onFolderAction: (FolderEntity, FolderAction) -> Unit,
    onManageRules: () -> Unit,
    onManageFolders: (Long) -> Unit,
    onManageAccounts: () -> Unit,
    onSettings: () -> Unit
) {
    ModalDrawerSheet {
        LazyColumn {
            item {
                Text(
                    "uwuMail",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(24.dp)
                )
            }

            // Cross-account views, before any individual account's folders.
            items(MailTarget.UNIFIED, key = { it.second }) { (destination, label) ->
                NavigationDrawerItem(
                    label = { Text(label) },
                    icon = {
                        Icon(
                            when (destination) {
                                MailTarget.OUTBOXES -> Icons.AutoMirrored.Filled.Send
                                MailTarget.DELETED -> Icons.Default.Delete
                                else -> Icons.Default.Inbox
                            },
                            contentDescription = null
                        )
                    },
                    selected = state.currentFolder == null && state.target == destination,
                    onClick = { onOpen(destination) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            state.accounts.forEach { account ->
                val folders = state.visibleFolders.filter { it.accountId == account.id }
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SectionHeader(account.displayName, Modifier.weight(1f))
                        IconButton(onClick = { onManageFolders(account.id) }) {
                            Icon(
                                painterResource(R.drawable.ic_folder_edit),
                                contentDescription = "Edit folders",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                itemsIndexed(folders, key = { _, folder -> folder.id }) { index, folder ->
                    FolderRow(
                        folder = folder,
                        selected = state.currentFolder?.id == folder.id,
                        canMoveUp = index > 0,
                        canMoveDown = index < folders.lastIndex,
                        onClick = { onOpen(MailTarget.Folder(folder.id)) },
                        onAction = { onFolderAction(folder, it) }
                    )
                }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(
                    label = { Text("Rules") },
                    icon = { Icon(Icons.Default.Rule, null) },
                    selected = false,
                    onClick = onManageRules,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Accounts") },
                    icon = { Icon(Icons.Default.Folder, null) },
                    selected = false,
                    onClick = onManageAccounts,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    icon = { Icon(Icons.Default.Settings, null) },
                    selected = false,
                    onClick = onSettings,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * A folder in the drawer. Long-pressing opens the same menu a desktop client
 * would put on right-click: reorder, hide, mark read.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    folder: FolderEntity,
    selected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onAction: (FolderAction) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else Color.Transparent
                )
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Icon(
                if (folder.isLocal) Icons.Default.PhoneAndroid else Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                folder.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            // Most folders are not background-synced, so marking the few that
            // are keeps the list quiet.
            if (folder.syncEnabled && !folder.isLocal) {
                Icon(
                    Icons.Default.Sync,
                    contentDescription = "Synced in the background",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
            }
            if (folder.unreadCount > 0) {
                Text(
                    folder.unreadCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            Text(
                folder.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Move up") },
                enabled = canMoveUp,
                leadingIcon = { Icon(Icons.Default.ArrowUpward, null) },
                onClick = { menuOpen = false; onAction(FolderAction.MOVE_UP) }
            )
            DropdownMenuItem(
                text = { Text("Move down") },
                enabled = canMoveDown,
                leadingIcon = { Icon(Icons.Default.ArrowDownward, null) },
                onClick = { menuOpen = false; onAction(FolderAction.MOVE_DOWN) }
            )
            DropdownMenuItem(
                text = { Text("Reset order") },
                onClick = { menuOpen = false; onAction(FolderAction.RESET_ORDER) }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Mark all as read") },
                leadingIcon = { Icon(Icons.Default.MarkEmailRead, null) },
                onClick = { menuOpen = false; onAction(FolderAction.MARK_READ) }
            )
            if (!folder.isLocal) {
                DropdownMenuItem(
                    text = { Text(if (folder.syncEnabled) "Stop syncing" else "Sync automatically") },
                    leadingIcon = { Icon(Icons.Default.Sync, null) },
                    onClick = { menuOpen = false; onAction(FolderAction.TOGGLE_SYNC) }
                )
            }
            DropdownMenuItem(
                text = { Text("Hide on this device") },
                leadingIcon = { Icon(Icons.Default.VisibilityOff, null) },
                onClick = { menuOpen = false; onAction(FolderAction.HIDE) }
            )
        }
    }
}
