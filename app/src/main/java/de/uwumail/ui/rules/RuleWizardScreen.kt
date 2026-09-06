package de.uwumail.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uwumail.R
import de.uwumail.core.ActionType
import de.uwumail.rules.Suggestion
import de.uwumail.sync.SyncManager
import de.uwumail.ui.common.EmptyState
import de.uwumail.ui.common.SectionHeader
import de.uwumail.ui.containerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleWizardScreen(
    messageIds: List<Long>,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit
) {
    val viewModel = containerViewModel(key = "wizard-${messageIds.joinToString(",")}") {
        RuleWizardViewModel(it, messageIds)
    }
    val state by viewModel.state.collectAsState()
    var actionMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wizard_create)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.save(onSaved) },
                        enabled = state.canSave
                    ) { Icon(Icons.Default.Save, stringResource(R.string.wizard_save)) }
                }
            )
        }
    ) { padding ->
        state.error?.let { failure ->
            EmptyState(
                title = stringResource(R.string.wizard_failed),
                subtitle = failure
            )
            return@Scaffold
        }
        if (state.analysing) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.wizard_looking),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            return@Scaffold
        }

        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "${state.samples.size} messages selected",
                            fontWeight = FontWeight.SemiBold
                        )
                        state.samples.take(3).forEach { message ->
                            Text(
                                "· ${message.subject.take(70).ifBlank { "(no subject)" }}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                        if (state.samples.size > 3) {
                            Text(
                                "· and ${state.samples.size - 3} more",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.wizard_common)) }

            if (state.suggestions.isEmpty()) {
                item {
                    EmptyState(
                        stringResource(R.string.wizard_nothing),
                        stringResource(R.string.wizard_nothing_body)
                    )
                }
            }

            itemsIndexed(state.suggestions) { index, suggestion ->
                SuggestionRow(
                    suggestion = suggestion,
                    checked = index in state.selected,
                    onToggle = { viewModel.toggleSuggestion(index) }
                )
            }

            if (state.suggestions.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                if (state.matchedOthers == 0) {
                                    stringResource(
                                        R.string.wizard_only_selection, state.samples.size
                                    )
                                } else {
                                    stringResource(
                                        R.string.wizard_also_matches, state.matchedOthers
                                    )
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.wizard_then)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    state.actions.forEachIndexed { index, action ->
                        AssistChip(
                            onClick = { viewModel.removeAction(index) },
                            label = { Text(action.label) },
                            trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.remove)) },
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                    TextButton(onClick = { actionMenu = true }) {
                        Icon(Icons.Default.Add, null)
                        Text(" " + stringResource(R.string.add_action))
                    }
                    DropdownMenu(
                        expanded = actionMenu,
                        onDismissRequest = { actionMenu = false }
                    ) {
                        WizardActionMenu(
                            folders = state.folders.filter {
                                state.accountId == null || it.accountId == state.accountId
                            },
                            onPick = { actionMenu = false; viewModel.addAction(it) }
                        )
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.section_name)) }
            item {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    label = { Text(stringResource(R.string.rule_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                )
            }
            item {
                state.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                Box(Modifier.padding(32.dp))
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    suggestion: Suggestion,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(suggestion.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                suggestion.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (suggestion.isExact) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (suggestion.condition.operator == "REGEX") {
                Text(
                    suggestion.condition.value,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WizardActionMenu(
    folders: List<de.uwumail.data.db.FolderEntity>,
    onPick: (PendingAction) -> Unit
) {
    var folderFor by remember { mutableStateOf<ActionType?>(null) }

    if (folderFor == null) {
        // The short list first: this is what people reach for after selecting mails.
        listOf(
            ActionType.SUPPRESS_NOTIFICATION,
            ActionType.NOTIFY_SILENT,
            ActionType.MARK_READ,
            ActionType.ARCHIVE,
            ActionType.MOVE_TO_TRASH,
            ActionType.FLAG,
            ActionType.DOWNLOAD,
            ActionType.DELETE_PERMANENTLY
        ).forEach { type ->
            val name = stringResource(type.label)
            DropdownMenuItem(
                text = { Text(name) },
                onClick = { onPick(PendingAction(type, null, name)) }
            )
        }
        listOf(
            ActionType.MOVE_TO_FOLDER,
            ActionType.COPY_TO_FOLDER,
            ActionType.MOVE_TO_LOCAL
        ).forEach { type ->
            DropdownMenuItem(
                text = { Text("${type.label}…") },
                onClick = { folderFor = type }
            )
        }
    } else {
        val type = folderFor!!
        val candidates = if (type == ActionType.MOVE_TO_LOCAL) folders.filter { it.isLocal }
        else folders.filter { !it.isLocal }
        if (candidates.isEmpty()) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.wizard_no_folder)) },
                onClick = { folderFor = null }
            )
        }
        candidates.forEach { folder ->
            DropdownMenuItem(
                text = { Text(folder.displayName) },
                onClick = {
                    val arg = if (folder.isLocal) SyncManager.localName(folder.path) else folder.path
                    folderFor = null
                    onPick(PendingAction(type, arg, "${type.label} → ${folder.displayName}"))
                }
            )
        }
    }
}
