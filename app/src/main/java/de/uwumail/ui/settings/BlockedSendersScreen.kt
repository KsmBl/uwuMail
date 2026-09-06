package de.uwumail.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uwumail.R
import de.uwumail.ui.LocalAppContainer
import de.uwumail.ui.common.EmptyState
import de.uwumail.ui.common.TextPromptDialog
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Everything blocked by hand, in full.
 *
 * The settings screen only ever showed the first dozen with "and N more" after
 * them, which meant a list that could be added to and never read back. A
 * blocklist you cannot audit is one you cannot trust.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedSendersScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val repository = container.blocklistRepository
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    val listId by produceState<Long?>(initialValue = null) {
        value = repository.manualListId()
    }
    val entries by (listId?.let { repository.observeEntries(it) } ?: flowOf(emptyList()))
        .collectAsState(initial = emptyList())

    var filter by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }

    val shown = remember(entries, filter) {
        if (filter.isBlank()) entries
        else entries.filter { it.contains(filter.trim(), ignoreCase = true) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.spam_blocked_senders))
                        Text(
                            stringResource(R.string.blocked_count, entries.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.spam_block_sender)) }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (entries.isNotEmpty()) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text(stringResource(R.string.blocked_filter)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            when {
                entries.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    EmptyState(
                        title = stringResource(R.string.blocked_none),
                        subtitle = stringResource(R.string.spam_blocked_hint)
                    )
                }
                shown.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    EmptyState(title = stringResource(R.string.no_matches))
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it }) { pattern ->
                        ListItem(
                            headlineContent = { Text(pattern) },
                            trailingContent = {
                                IconButton(onClick = {
                                    scope.launch {
                                        repository.unblockSender(pattern)
                                        snackbarHost.showSnackbar(pattern)
                                    }
                                }) {
                                    Icon(
                                        Icons.Default.Close,
                                        stringResource(R.string.blocked_unblock)
                                    )
                                }
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
            }
        }
    }

    if (adding) {
        TextPromptDialog(
            title = stringResource(R.string.spam_block_sender),
            label = stringResource(R.string.spam_domain_or_address),
            confirmLabel = stringResource(R.string.spam_block),
            supportingText = stringResource(R.string.spam_block_hint),
            onConfirm = { value -> scope.launch { repository.blockSender(value) } },
            onDismiss = { adding = false }
        )
    }
}
