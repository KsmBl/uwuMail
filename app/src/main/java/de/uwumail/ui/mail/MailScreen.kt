package de.uwumail.ui.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileCopy
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import de.uwumail.R
import de.uwumail.data.db.FolderEntity
import de.uwumail.data.db.MessageSummary
import de.uwumail.core.SwipeAction
import de.uwumail.sync.UndoKind
import de.uwumail.sync.Undoable
import de.uwumail.ui.common.rememberHaptics
import de.uwumail.ui.common.ConfirmDialog
import de.uwumail.ui.common.EmptyState
import de.uwumail.ui.common.FolderPickerSheet
import de.uwumail.ui.common.SectionHeader
import de.uwumail.ui.common.formatListDate
import de.uwumail.ui.containerViewModel
import android.widget.Toast
import de.uwumail.ui.mail.gravity.rememberDeviceOrientation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAPS_TO_UNLOCK = 5
private const val TAP_GAP_MILLIS = 1_500L

/** The name of a unified view, which has no folder of its own to be named after. */
@StringRes
private fun unifiedTitle(target: MailTarget): Int = when (target) {
    MailTarget.Search -> R.string.search_results
    else -> MailTarget.UNIFIED.firstOrNull { it.first == target }?.second ?: R.string.all_inboxes
}

/**
 * Where closing the search box lands, or null to stay where the list already is.
 *
 * Widening a search to every folder moves the list off the folder it was on, so
 * closing it has to put that back; a search that never left the folder has
 * nothing to undo.
 */
fun targetAfterSearch(current: MailTarget, origin: MailTarget?): MailTarget? =
    if (current == MailTarget.Search) origin ?: MailTarget.INBOXES else null

/** How a removal that is still cancellable describes itself. */
@Composable
private fun undoMessage(offer: Undoable): String = pluralStringResource(
    when (offer.kind) {
        UndoKind.ARCHIVE -> R.plurals.undo_archived
        UndoKind.TRASH -> R.plurals.undo_trashed
        UndoKind.DELETE -> R.plurals.undo_deleted
        UndoKind.MOVE -> R.plurals.undo_moved
    },
    offer.count,
    offer.count
)

/** What the list is looking at in each slot, so it reuses like for like. */
/** How long the list gets to itself before the browser engine is started. */
private const val WARM_UP_DELAY = 1_200L

/** How close to an edge a drag has to be before the list starts scrolling. */
private const val EDGE_PIXELS = 120f

/** The fastest the list scrolls under a drag, in pixels per frame. */
private const val MAX_SCROLL_PIXELS = 28f

private const val DAY_HEADER = "day-header"
private const val MESSAGE_ROW = "message-row"

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MailScreen(
    onOpenMessage: (Long) -> Unit,
    onOpenDraft: (Long) -> Unit,
    onCompose: (Long?) -> Unit,
    onManageRules: () -> Unit,
    onManageFolders: (Long) -> Unit,
    onManageAccounts: () -> Unit,
    onSettings: () -> Unit,
    onCreateRuleFrom: (List<Long>) -> Unit
) {
    val viewModel = containerViewModel { MailViewModel(it) }
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    // Read here rather than inside the callbacks below, which are not composable.
    val undoLabel = stringResource(R.string.undo)
    val gravityUnlockedMessage = stringResource(R.string.gravity_unlocked)

    var showMovePicker by remember { mutableStateOf(false) }
    var showCopyPicker by remember { mutableStateOf(false) }
    // A swipe that needs an answer before it can finish.
    var swipeMoveFor by remember { mutableStateOf<Long?>(null) }
    var swipeDeleteFor by remember { mutableStateOf<Long?>(null) }
    // What the bin button is about to do to a selection that includes mail
    // already in the bin: the counts are taken when it is pressed, so they do
    // not go to zero underneath the dialog as the selection clears.
    var confirmSelectionTrash by remember {
        mutableStateOf<Pair<List<Long>, List<Long>>?>(null)
    }
    // The bin whose emptying is being asked about.
    var confirmEmpty by remember { mutableStateOf<FolderEntity?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var searchOrigin by remember { mutableStateOf<MailTarget?>(null) }
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

    // The engine behind a mail body takes a while to start, so it is started
    // here rather than when the reader is already looking at an empty screen.
    // Not straight away: the list's own first frames come first.
    LaunchedEffect(Unit) {
        delay(WARM_UP_DELAY)
        WebViewWarmup.start(context)
    }

    // Day headings, recomputed only when the list itself changes.
    val sections = remember(state.messages) { groupByDay(state.messages) }

    // Which folders are a bin, so a row can say whether trashing it again
    // would be a permanent deletion. Recomputed only when the folders do.
    val binFolders = remember(state.folders, state.accounts) { state.binFolderIds() }

    // A long press that turns into a drag picks out everything it passes over.
    // The anchor is where it started; the base is what was already picked out,
    // which the drag adds to rather than replaces.
    val listHaptics = rememberHaptics()
    var dragAnchor by remember { mutableStateOf<Long?>(null) }
    var dragBase by remember { mutableStateOf(emptySet<Long>()) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var listHeight by remember { mutableFloatStateOf(0f) }

    /** The message rows on screen, with where each one currently sits. */
    fun visibleRows(): List<RowBounds> = listState.layoutInfo.visibleItemsInfo
        .filter { it.contentType == MESSAGE_ROW }
        .mapNotNull { info ->
            (info.key as? Long)?.let { RowBounds(it, info.offset, info.offset + info.size) }
        }

    /** Extends the selection to whatever the finger is over now. */
    fun reachTo(y: Float) {
        val anchor = dragAnchor ?: return
        rowAt(visibleRows(), y)?.let { viewModel.selectRange(dragBase, anchor, it) }
    }

    // Held against an edge the list carries on scrolling, and the selection
    // carries on growing even though the finger is no longer moving.
    LaunchedEffect(dragAnchor) {
        if (dragAnchor == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val speed = autoScrollSpeed(pointerY, listHeight, EDGE_PIXELS, MAX_SCROLL_PIXELS)
            if (speed != 0f) {
                listState.scrollBy(speed)
                reachTo(pointerY)
            }
        }
    }

    // Pull the next page in once the user nears the end of the cached list.
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            // Counted from the layout rather than from the message list, which
            // no longer matches the row count now that days have headings.
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 5
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
                drawerOpen = drawerState.isOpen,
                onUnlocked = {
                    if (viewModel.unlockGravity()) {
                        Toast.makeText(context, gravityUnlockedMessage, Toast.LENGTH_SHORT).show()
                    }
                },
                onOpen = {
                    viewModel.open(it)
                    scope.launch { drawerState.close() }
                },
                binFolders = binFolders,
                onFolderAction = { folder, action ->
                    // Emptying cannot be taken back, so it is the one folder
                    // action that stops to ask.
                    if (action == FolderAction.EMPTY) confirmEmpty = folder
                    else viewModel.folderAction(folder, action)
                },
                onManageRules = { scope.launch { drawerState.close() }; onManageRules() },
                onManageFolders = { scope.launch { drawerState.close() }; onManageFolders(it) },
                onManageAccounts = { scope.launch { drawerState.close() }; onManageAccounts() },
                onSettings = { scope.launch { drawerState.close() }; onSettings() }
            )
        }
    ) {
        Scaffold(
            snackbarHost = {
                // The undo offers stack above the ordinary snackbar: several
                // removals can be waiting at once, and each is a separate
                // question with its own few seconds to answer it.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.undos.forEach { offer ->
                        Snackbar(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            action = {
                                TextButton(onClick = { viewModel.undo(offer.token) }) {
                                    Text(undoLabel, color = MaterialTheme.colorScheme.inversePrimary)
                                }
                            },
                            dismissAction = {
                                IconButton(onClick = { viewModel.dismissUndo(offer.token) }) {
                                    Icon(Icons.Default.Close, stringResource(R.string.dismiss))
                                }
                            }
                        ) { Text(undoMessage(offer)) }
                    }
                    SnackbarHost(snackbarHost)
                }
            },
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
                        onTrash = {
                            val split = state.selectionByBin()
                            if (split.first.isEmpty()) viewModel.trashSelection()
                            else confirmSelectionTrash = split
                        },
                        onDeleteForever = viewModel::deleteSelectionPermanently,
                        onMove = { showMovePicker = true },
                        onCopy = { showCopyPicker = true },
                        onDownload = viewModel::downloadSelection,
                        onSaveAttachments = viewModel::saveSelectionAttachments,
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
                                    placeholder = {
                                        Text(
                                            when (state.target) {
                                                MailTarget.Search ->
                                                    stringResource(R.string.search_everywhere)
                                                is MailTarget.Unified ->
                                                    stringResource(R.string.search_these_folders)
                                                is MailTarget.Folder ->
                                                    stringResource(R.string.search_folder)
                                            }
                                        )
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                Column {
                                    Text(
                            state.currentFolder?.displayName
                                ?: stringResource(unifiedTitle(state.target)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
                                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.folders))
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                if (!showSearch) {
                                    // Where the search was started from, so
                                    // closing it comes back here rather than
                                    // dropping into the inbox from a folder the
                                    // user was half way down.
                                    searchOrigin = state.target
                                    showSearch = true
                                } else {
                                    showSearch = false
                                    viewModel.setQuery("")
                                    // Leaving the search leaves the results too.
                                    targetAfterSearch(state.target, searchOrigin)
                                        ?.let(viewModel::open)
                                    searchOrigin = null
                                }
                            }) {
                                Icon(
                                    if (showSearch) Icons.Default.Close else Icons.Default.Search,
                                    contentDescription = stringResource(R.string.search)
                                )
                            }
                            IconButton(onClick = viewModel::refresh) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                            }
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Default.Rule, contentDescription = stringResource(R.string.more))
                            }
                            DropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false }
                            ) {
                                if (showSearch && state.query.isNotBlank()) {
                                    if (state.target != MailTarget.Search) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.search_all_folders)) },
                                            leadingIcon = { Icon(Icons.Default.Search, null) },
                                            onClick = {
                                                overflowOpen = false
                                                viewModel.searchEverywhere()
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.search_on_server)) },
                                        leadingIcon = { Icon(Icons.Default.CloudDownload, null) },
                                        onClick = {
                                            overflowOpen = false
                                            viewModel.searchOnServer()
                                        }
                                    )
                                    HorizontalDivider()
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rules)) },
                                    onClick = { overflowOpen = false; onManageRules() }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.manage_folders)) },
                                    onClick = {
                                        overflowOpen = false
                                        state.currentFolder?.accountId
                                            ?.let(onManageFolders)
                                            ?: state.accounts.firstOrNull()?.let { onManageFolders(it.id) }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.accounts)) },
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
                        text = { Text(stringResource(R.string.compose)) }
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
                                    title = stringResource(R.string.no_accounts),
                                    subtitle = stringResource(R.string.no_accounts_sub)
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
                                    title = if (state.query.isBlank()) stringResource(R.string.nothing_here)
                                    else stringResource(R.string.no_matches),
                                    subtitle = if (state.query.isBlank()) stringResource(R.string.pull_to_sync)
                                    else null
                                )
                            }
                        }
                    }
                    else -> LazyColumn(
                        state = listState,
                        // While a drag is picking mail out, the list must not
                        // also read it as a scroll: the inner scroller sees a
                        // gesture before the detector wrapped around it does,
                        // and would swallow every drag after the long press.
                        // Scrolling still happens here, driven by the edge
                        // below rather than by the finger.
                        userScrollEnabled = dragAnchor == null,
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { listHeight = it.height.toFloat() }
                            .pointerInput(Unit) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { start ->
                                        val id = rowAt(visibleRows(), start.y)
                                        if (id != null) {
                                            listHaptics.longPress()
                                            pointerY = start.y
                                            dragBase = viewModel.currentSelection()
                                            dragAnchor = id
                                            viewModel.selectRange(dragBase, id, id)
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        pointerY = change.position.y
                                        reachTo(pointerY)
                                    },
                                    onDragEnd = { dragAnchor = null },
                                    onDragCancel = { dragAnchor = null }
                                )
                            }
                    ) {
                        sections.forEach { section ->
                            stickyHeader(
                                key = section.dayStart,
                                contentType = DAY_HEADER
                            ) {
                                val ids = remember(section) { section.messages.map { it.id } }
                                DayHeader(
                                    label = section.label,
                                    allSelected = state.selection.containsAll(ids),
                                    onClick = { viewModel.toggleDay(ids) }
                                )
                            }
                            // The content types tell the list that a heading and
                            // a row are different things, so it reuses each kind
                            // for its own kind instead of rebuilding whatever it
                            // last had at that slot.
                            items(
                                section.messages,
                                key = { it.id },
                                contentType = { MESSAGE_ROW }
                            ) { message ->
                                // Removals collapse and arrivals slide in rather
                                // than the list jumping to its new shape.
                                // A row already in the bin has nowhere left to be
                                // trashed to, so the gesture becomes the deletion
                                // it was reaching for — which asks first.
                                val inBin = message.folderId in binFolders
                                Column(Modifier.animateItem()) {
                                    SwipeableMessageRow(
                                        // While messages are being picked out, only
                                        // swipes that pick out more of them still
                                        // make sense: archiving one row while others
                                        // sit selected and untouched does not.
                                        rightAction = state.swipeRight.inBin(inBin)
                                            .takeIf {
                                                !state.inSelectionMode || it.worksWhileSelecting
                                            } ?: SwipeAction.NONE,
                                        leftAction = state.swipeLeft.inBin(inBin)
                                            .takeIf {
                                                !state.inSelectionMode || it.worksWhileSelecting
                                            } ?: SwipeAction.NONE,
                                        seen = message.seen,
                                        flagged = message.flagged,
                                        enabled = true,
                                        commitFraction = state.swipeThreshold,
                                        onAction = { action ->
                                            when (action) {
                                                SwipeAction.MOVE -> swipeMoveFor = message.id
                                                SwipeAction.DELETE -> swipeDeleteFor = message.id
                                                else -> viewModel.applySwipe(message, action)
                                            }
                                        }
                                    ) {
                                        MessageRow(
                                            message = message,
                                            selected = message.id in state.selection,
                                            selectionMode = state.inSelectionMode,
                                            accountColor = state.accounts
                                                .firstOrNull { it.id == message.accountId }?.color,
                                            onClick = {
                                                when {
                                                    state.inSelectionMode ->
                                                        viewModel.toggleSelection(message.id)
                                                    state.showsDrafts -> onOpenDraft(message.id)
                                                    else -> onOpenMessage(message.id)
                                                }
                                            },

                                            onPick = { viewModel.toggleSelection(message.id) },
                                            onStar = { viewModel.toggleStar(message.id, !message.flagged) }
                                        )
                                    }
                                    HorizontalDivider(thickness = 0.5.dp)
                                }
                            }
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

    swipeMoveFor?.let { messageId ->
        FolderPickerSheet(
            folders = state.moveTargets(),
            accounts = state.accounts,
            title = stringResource(R.string.move_to),
            confirmLabel = stringResource(R.string.move_here),
            preferredAccountId = state.currentFolder?.accountId,
            onPick = { swipeMoveFor = null; viewModel.moveMessage(messageId, it.id) },
            onDismiss = { swipeMoveFor = null }
        )
    }

    confirmEmpty?.let { folder ->
        ConfirmDialog(
            title = stringResource(R.string.empty_bin_q),
            message = pluralStringResource(
                R.plurals.empty_bin_body, folder.totalCount, folder.totalCount
            ),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = { viewModel.folderAction(folder, FolderAction.EMPTY) },
            onDismiss = { confirmEmpty = null }
        )
    }

    confirmSelectionTrash?.let { (inBin, elsewhere) ->
        ConfirmDialog(
            title = stringResource(R.string.delete_forever_q),
            message = if (elsewhere.isEmpty()) {
                pluralStringResource(R.plurals.trash_confirm_all, inBin.size, inBin.size)
            } else {
                stringResource(R.string.trash_confirm_mixed, inBin.size, elsewhere.size)
            },
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = viewModel::trashSelection,
            onDismiss = { confirmSelectionTrash = null }
        )
    }

    swipeDeleteFor?.let { messageId ->
        ConfirmDialog(
            title = stringResource(R.string.delete_forever_q),
            message = stringResource(R.string.delete_forever_body),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = { viewModel.deleteMessage(messageId) },
            onDismiss = { swipeDeleteFor = null }
        )
    }

    if (showCopyPicker) {
        FolderPickerSheet(
            folders = state.moveTargets(),
            accounts = state.accounts,
            title = stringResource(R.string.copy_to),
            confirmLabel = stringResource(R.string.copy_here),
            preferredAccountId = state.currentFolder?.accountId,
            onPick = {
                showCopyPicker = false
                viewModel.copySelection(it.id)
            },
            onDismiss = { showCopyPicker = false }
        )
    }

    if (showMovePicker) {
        FolderPickerSheet(
            folders = state.moveTargets(),
            accounts = state.accounts,
            title = stringResource(R.string.move_to),
            confirmLabel = stringResource(R.string.move_here),
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
    onCopy: () -> Unit,
    onDownload: () -> Unit,
    onSaveAttachments: () -> Unit,
    onCreateRule: () -> Unit
) {
    var overflow by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = {
            IconButton(onClick = onClear) { Icon(Icons.Default.Close, stringResource(R.string.clear_selection)) }
        },
        actions = {
            IconButton(onClick = onArchive) { Icon(Icons.Default.Archive, stringResource(R.string.archive)) }
            IconButton(onClick = onTrash) { Icon(Icons.Default.Delete, stringResource(R.string.trash)) }
            IconButton(onClick = onMove) { Icon(Icons.Default.DriveFileMove, stringResource(R.string.move)) }
            IconButton(onClick = onCreateRule) {
                Icon(Icons.AutoMirrored.Filled.Label, stringResource(R.string.create_rule_from))
            }
            IconButton(onClick = { overflow = true }) { Icon(Icons.Default.Rule, stringResource(R.string.more)) }
            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mark_read)) },
                    leadingIcon = { Icon(Icons.Default.MarkEmailRead, null) },
                    onClick = { overflow = false; onMarkRead() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mark_unread)) },
                    leadingIcon = { Icon(Icons.Default.MarkEmailUnread, null) },
                    onClick = { overflow = false; onMarkUnread() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.star)) },
                    leadingIcon = { Icon(Icons.Default.Star, null) },
                    onClick = { overflow = false; onStar() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.copy_to_folder)) },
                    leadingIcon = { Icon(Icons.Default.FileCopy, null) },
                    onClick = { overflow = false; onCopy() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.save_attachments)) },
                    leadingIcon = { Icon(Icons.Default.AttachFile, null) },
                    onClick = { overflow = false; onSaveAttachments() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.download_to_device)) },
                    leadingIcon = { Icon(Icons.Default.Download, null) },
                    onClick = { overflow = false; onDownload() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.select_all)) },
                    leadingIcon = { Icon(Icons.Default.Check, null) },
                    onClick = { overflow = false; onSelectAll() }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.delete_permanently), color = MaterialTheme.colorScheme.error)
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

/**
 * The date a run of messages arrived on, pinned to the top of the list while
 * that day is on screen so a long scroll always says which day it is showing.
 *
 * It is also the handle for the whole day: a day is the grouping the list
 * already draws, so the line naming it is where anyone reaches to take the lot.
 * It wears the selected colour once its day is entirely picked out, which is
 * both the confirmation and the invitation to tap again and give it back.
 */
@Composable
private fun DayHeader(label: String, allSelected: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Surface(
        color = if (allSelected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.select_day)) {
                haptics.confirm()
                onClick()
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (allSelected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (allSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: MessageSummary,
    selected: Boolean,
    selectionMode: Boolean,
    accountColor: Int?,
    onClick: () -> Unit,
    /** Tapping the avatar picks the row out, the way a long press used to. */
    onPick: () -> Unit,
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
    val haptics = rememberHaptics()
    // Selection begins under the finger and nothing moves when it does, so the
    // knock is the only thing that says the press registered.
    val pick = { haptics.longPress(); onPick() }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            // No long press here on purpose: the list itself detects it, so
            // that the drag which follows keeps arriving. A child consuming
            // the press would end the gesture where it starts.
            .clickable(onClick = onClick)
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
                .clickable(onClick = pick),
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
                        contentDescription = stringResource(R.string.sender_on_spam_list),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    message.fromName?.takeIf { it.isNotBlank() }
                        ?: message.fromAddress.orEmpty().ifBlank { stringResource(R.string.unknown_sender) },
                    style = MaterialTheme.typography.titleSmall,
                    color = senderColor,
                    fontWeight = if (message.seen) FontWeight.Normal else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (message.answered) {
                    Icon(
                        Icons.AutoMirrored.Filled.Reply,
                        contentDescription = stringResource(R.string.replied_to),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (message.isLocal) {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = stringResource(R.string.stored_on_device),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (message.hasAttachments) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = stringResource(R.string.has_attachments),
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
                message.subject.ifBlank { stringResource(R.string.no_subject) },
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
                contentDescription = stringResource(R.string.star),
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
    drawerOpen: Boolean,
    onUnlocked: () -> Unit,
    onOpen: (MailTarget) -> Unit,
    binFolders: Set<Long>,
    onFolderAction: (FolderEntity, FolderAction) -> Unit,
    onManageRules: () -> Unit,
    onManageFolders: (Long) -> Unit,
    onManageAccounts: () -> Unit,
    onSettings: () -> Unit
) {
    val upsideDown = rememberDeviceOrientation(active = drawerOpen).value.upsideDown
    var taps by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }

    // The run of taps only counts while the phone is held that way, and a
    // pause between taps starts it over.
    LaunchedEffect(upsideDown) { if (!upsideDown) taps = 0 }

    ModalDrawerSheet {
        LazyColumn {
            item {
                Text(
                    "uwuMail",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            val now = System.currentTimeMillis()
                            taps = if (upsideDown && now - lastTapAt < TAP_GAP_MILLIS) taps + 1 else 1
                            lastTapAt = now
                            if (upsideDown && taps >= TAPS_TO_UNLOCK) {
                                taps = 0
                                onUnlocked()
                            }
                        }
                        .padding(24.dp)
                )
            }

            // Cross-account views, before any individual account's folders.
            items(MailTarget.UNIFIED, key = { it.second }) { (destination, label) ->
                NavigationDrawerItem(
                    label = { Text(stringResource(label)) },
                    icon = {
                        Icon(
                            when (destination) {
                                MailTarget.SENT -> Icons.AutoMirrored.Filled.Send
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
                                contentDescription = stringResource(R.string.edit_folders),
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
                        isBin = folder.id in binFolders,
                        onAction = { onFolderAction(folder, it) }
                    )
                }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.rules)) },
                    icon = { Icon(Icons.Default.Rule, null) },
                    selected = false,
                    onClick = onManageRules,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.accounts)) },
                    icon = { Icon(Icons.Default.Folder, null) },
                    selected = false,
                    onClick = onManageAccounts,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.settings)) },
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
    /** Whether this is where the account puts deleted mail, and so can be emptied. */
    isBin: Boolean,
    onClick: () -> Unit,
    onAction: (FolderAction) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()

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
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { haptics.longPress(); menuOpen = true }
                )
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
                    contentDescription = stringResource(R.string.synced_in_background),
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
                text = { Text(stringResource(R.string.move_up)) },
                enabled = canMoveUp,
                leadingIcon = { Icon(Icons.Default.ArrowUpward, null) },
                onClick = { menuOpen = false; onAction(FolderAction.MOVE_UP) }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.move_down)) },
                enabled = canMoveDown,
                leadingIcon = { Icon(Icons.Default.ArrowDownward, null) },
                onClick = { menuOpen = false; onAction(FolderAction.MOVE_DOWN) }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.reset_order)) },
                onClick = { menuOpen = false; onAction(FolderAction.RESET_ORDER) }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mark_all_read)) },
                leadingIcon = { Icon(Icons.Default.MarkEmailRead, null) },
                onClick = { menuOpen = false; onAction(FolderAction.MARK_READ) }
            )
            if (!folder.isLocal) {
                DropdownMenuItem(
                    text = { Text(if (folder.syncEnabled) stringResource(R.string.stop_syncing) else stringResource(R.string.sync_automatically)) },
                    leadingIcon = { Icon(Icons.Default.Sync, null) },
                    onClick = { menuOpen = false; onAction(FolderAction.TOGGLE_SYNC) }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.hide_on_device)) },
                leadingIcon = { Icon(Icons.Default.VisibilityOff, null) },
                onClick = { menuOpen = false; onAction(FolderAction.HIDE) }
            )
            // Only the bin: everywhere else this would be a way to lose mail
            // that was not on its way out in the first place.
            if (isBin) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.empty_bin),
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.DeleteSweep,
                            null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = { menuOpen = false; onAction(FolderAction.EMPTY) }
                )
            }
        }
    }
}
