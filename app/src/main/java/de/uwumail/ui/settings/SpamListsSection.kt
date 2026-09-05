package de.uwumail.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
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
fun SpamListsSection(onMessage: (String) -> Unit) {
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
            "Mail from a listed sender is highlighted in red. Nothing is deleted " +
                "or moved — use a rule if you want that.",
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
                                list.lastError != null -> "Update failed: ${list.lastError}"
                                list.entryCount > 0 ->
                                    "${list.entryCount} domains · updated " +
                                        formatListDate(list.updatedAt)
                                list.enabled -> "Not downloaded yet"
                                else -> "Off"
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
                            Icon(Icons.Default.Close, "Remove list")
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
            Text(" Add list from URL")
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Blocked senders",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { addingSender = true }) {
                Icon(Icons.Default.Add, null)
                Text(" Add")
            }
        }
        if (manualEntries.isEmpty()) {
            Text(
                "Add a domain like example.com, or a single address.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                manualEntries.take(12).forEach { pattern ->
                    AssistChip(
                        onClick = { scope.launch { repository.unblockSender(pattern) } },
                        label = { Text(pattern) },
                        trailingIcon = { Icon(Icons.Default.Close, "Unblock $pattern") }
                    )
                }
            }
            if (manualEntries.size > 12) {
                Text(
                    "and ${manualEntries.size - 12} more",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }

    if (addingList) {
        TextPromptDialog(
            title = "Add spam list",
            label = "URL",
            confirmLabel = "Add",
            supportingText = "A plain text list with one domain per line",
            onConfirm = { url ->
                scope.launch {
                    runCatching { repository.addCustomList(url.substringAfterLast('/'), url) }
                        .onSuccess { onMessage("List added") }
                        .onFailure { onMessage("Could not add that list: ${it.message}") }
                }
            },
            onDismiss = { addingList = false }
        )
    }
    if (addingSender) {
        TextPromptDialog(
            title = "Block sender",
            label = "Domain or address",
            confirmLabel = "Block",
            supportingText = "example.com blocks the whole domain",
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
            message = "The downloaded domains are deleted from this device.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = { scope.launch { repository.deleteList(list.id) } },
            onDismiss = { deleting = null }
        )
    }
}
