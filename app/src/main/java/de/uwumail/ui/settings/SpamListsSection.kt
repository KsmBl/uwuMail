package de.uwumail.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ListItem
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uwumail.R
import de.uwumail.data.db.BlocklistEntity
import de.uwumail.ui.LocalAppContainer
import de.uwumail.ui.common.ConfirmDialog
import de.uwumail.ui.common.TextPromptDialog
import de.uwumail.ui.common.formatListDate
import kotlinx.coroutines.launch

/**
 * Spam list settings.
 *
 * Enabling a list downloads it; mail from a listed sender is drawn in red in the
 * message list. Nothing is deleted or filtered on the strength of a list — the
 * mail is still there, just flagged.
 */
@Composable
fun SpamListsSection(onManageBlocked: () -> Unit, onMessage: (String) -> Unit) {
    val container = LocalAppContainer.current
    val repository = container.blocklistRepository
    val scope = rememberCoroutineScope()
    val lists by repository.observeAll().collectAsState(initial = emptyList())

    var refreshing by remember { mutableStateOf<Long?>(null) }
    var addingList by remember { mutableStateOf(false) }
    var addingSender by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<BlocklistEntity?>(null) }

    val manualList = lists.firstOrNull { it.url == null }
    val manualEntries by (manualList?.let { repository.observeEntries(it.id) }
        ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsState(initial = emptyList())

    Column {
        Text(
            stringResource(R.string.spam_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        lists.filter { it.url != null }.forEach { list ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(list.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            list.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            when {
                                list.lastError != null ->
                                    stringResource(R.string.spam_update_failed, list.lastError)
                                list.entryCount > 0 -> stringResource(
                                    R.string.spam_entry_summary,
                                    list.entryCount,
                                    formatListDate(list.updatedAt)
                                )
                                list.enabled -> stringResource(R.string.spam_not_downloaded)
                                else -> stringResource(R.string.spam_off)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (list.lastError != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (refreshing == list.id) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                    } else {
                        IconButton(
                            onClick = {
                                refreshing = list.id
                                scope.launch {
                                    val result = repository.refresh(list.id)
                                    refreshing = null
                                    onMessage(
                                        result.fold(
                                            { "${list.name}: $it domains" },
                                            { "${list.name} failed: ${it.message}" }
                                        )
                                    )
                                }
                            }
                        ) { Icon(Icons.Default.Refresh, "Update ${list.name}") }
                    }
                    Switch(
                        checked = list.enabled,
                        onCheckedChange = { enabled ->
                            if (enabled && list.entryCount == 0) refreshing = list.id
                            scope.launch {
                                repository.setEnabled(list.id, enabled)
                                refreshing = null
                            }
                        }
                    )
                    if (!list.builtIn) {
                        IconButton(onClick = { deleting = list }) {
                            Icon(Icons.Default.Close, stringResource(R.string.spam_remove_list))
                        }
                    }
                }
            }
        }

        TextButton(
            onClick = { addingList = true },
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Icon(Icons.Default.Add, null)
            Text(" " + stringResource(R.string.add_list_from_url))
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.spam_blocked_senders),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
        }
        ListItem(
            modifier = Modifier.clickable(onClick = onManageBlocked),
            headlineContent = { Text(stringResource(R.string.blocked_manage)) },
            supportingContent = {
                Text(
                    if (manualEntries.isEmpty()) stringResource(R.string.spam_blocked_hint)
                    else stringResource(R.string.blocked_count, manualEntries.size)
                )
            },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        )
    }

    // Read outside the coroutine below, which is not composable.
    val listAdded = stringResource(R.string.spam_list_added)
    val listAddFailed = stringResource(R.string.spam_list_add_failed)
    if (addingList) {
        TextPromptDialog(
            title = stringResource(R.string.spam_add_list),
            label = stringResource(R.string.spam_url),
            confirmLabel = stringResource(R.string.add),
            supportingText = stringResource(R.string.spam_url_hint),
            onConfirm = { url ->
                scope.launch {
                    runCatching { repository.addCustomList(url.substringAfterLast('/'), url) }
                        .onSuccess { onMessage(listAdded) }
                        .onFailure { onMessage(listAddFailed.format(it.message.orEmpty())) }
                }
            },
            onDismiss = { addingList = false }
        )
    }
    if (addingSender) {
        TextPromptDialog(
            title = stringResource(R.string.spam_block_sender),
            label = stringResource(R.string.spam_domain_or_address),
            confirmLabel = stringResource(R.string.spam_block),
            supportingText = stringResource(R.string.spam_block_hint),
            onConfirm = { value ->
                scope.launch {
                    repository.blockSender(value)
                    onMessage("Blocked $value")
                }
            },
            onDismiss = { addingSender = false }
        )
    }
    deleting?.let { list ->
        ConfirmDialog(
            title = "Remove \"${list.name}\"?",
            message = stringResource(R.string.spam_remove_confirm),
            confirmLabel = stringResource(R.string.remove),
            destructive = true,
            onConfirm = { scope.launch { repository.deleteList(list.id) } },
            onDismiss = { deleting = null }
        )
    }
}
