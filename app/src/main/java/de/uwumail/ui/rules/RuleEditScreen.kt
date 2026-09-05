package de.uwumail.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.uwumail.core.ActionType
import de.uwumail.core.MatchMode
import de.uwumail.core.RuleField
import de.uwumail.core.RuleOperator
import de.uwumail.data.db.FolderEntity
import de.uwumail.data.db.RuleConditionEntity
import de.uwumail.rules.RuleMatcher
import de.uwumail.sync.SyncManager
import de.uwumail.ui.common.SectionHeader
import de.uwumail.ui.containerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditScreen(ruleId: Long, onBack: () -> Unit) {
    val viewModel = containerViewModel(key = "rule-$ruleId") { RuleEditViewModel(it, ruleId) }
    val state by viewModel.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    var addActionOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) { if (state.saved) onBack() }
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
                title = { Text(if (ruleId > 0) "Edit rule" else "New rule") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::save, enabled = state.canSave) {
                        Icon(Icons.Default.Save, "Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                label = { Text("Rule name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Enabled", Modifier.weight(1f))
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { v -> viewModel.update { it.copy(enabled = v) } }
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Stop after this rule",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = state.stopProcessing,
                    onCheckedChange = { v -> viewModel.update { it.copy(stopProcessing = v) } }
                )
            }

            SectionHeader("Applies to")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScopeDropdown(
                    label = state.accounts.firstOrNull { it.id == state.accountId }?.displayName
                        ?: "All accounts",
                    options = listOf<Pair<String, Long?>>("All accounts" to null) +
                        state.accounts.map { it.displayName to it.id },
                    onPick = { value -> viewModel.update { it.copy(accountId = value, folderPath = null) } },
                    modifier = Modifier.weight(1f)
                )
                ScopeDropdown(
                    label = state.folderPath ?: "All folders",
                    options = listOf<Pair<String, String?>>("All folders" to null) +
                        state.foldersForScope().map { it.displayName to it.path },
                    onPick = { value -> viewModel.update { it.copy(folderPath = value) } },
                    modifier = Modifier.weight(1f)
                )
            }

            SectionHeader("Conditions")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MatchMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.matchMode == mode,
                        onClick = { viewModel.update { it.copy(matchMode = mode) } },
                        label = { Text(if (mode == MatchMode.ALL) "Match all" else "Match any") }
                    )
                }
            }

            state.conditions.forEachIndexed { index, condition ->
                ConditionCard(
                    condition = condition,
                    onChange = { viewModel.updateCondition(index, it) },
                    onRemove = { viewModel.removeCondition(index) }
                )
            }
            TextButton(
                onClick = viewModel::addCondition,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Icon(Icons.Default.Add, null)
                Text(" Add condition")
            }

            SectionHeader("Then do")
            state.actions.forEachIndexed { index, action ->
                val type = runCatching { ActionType.valueOf(action.type) }.getOrNull()
                AssistChip(
                    onClick = { viewModel.removeAction(index) },
                    label = {
                        Text(
                            action.stringArg?.let { "${type?.label ?: action.type} → $it" }
                                ?: (type?.label ?: action.type)
                        )
                    },
                    trailingIcon = { Icon(Icons.Default.Close, "Remove") },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = { addActionOpen = true }) {
                    Icon(Icons.Default.Add, null)
                    Text(" Add action")
                }
                DropdownMenu(
                    expanded = addActionOpen,
                    onDismissRequest = { addActionOpen = false }
                ) {
                    ActionMenu(
                        folders = state.foldersForScope(),
                        onPick = { type, arg ->
                            addActionOpen = false
                            viewModel.addAction(type, arg)
                        }
                    )
                }
            }

            SectionHeader("Before you save")
            Column(
                Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = viewModel::preview,
                    enabled = state.conditions.any { it.value.isNotBlank() } && !state.previewing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.previewing) CircularProgressIndicator(Modifier.size(16.dp))
                    else Icon(Icons.Default.PlayArrow, null)
                    Text("  Test against cached mail")
                }
                state.previewCount?.let { count ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "Matches $count of ${state.previewTotal} cached messages",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (count == 0) {
                                    "Nothing matched. Loosen a condition, or sync more mail first."
                                } else {
                                    "Check that this is the set you meant before enabling " +
                                        "destructive actions."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (ruleId > 0) {
                    FilledTonalButton(
                        onClick = viewModel::applyToExistingMail,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Apply rules to mail already synced") }
                }
            }
            Column(Modifier.padding(24.dp)) { Text("") }
        }
    }
}

@Composable
private fun <T> ScopeDropdown(
    label: String,
    options: List<Pair<String, T>>,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label, maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, value) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { open = false; onPick(value) }
                )
            }
        }
    }
}

@Composable
private fun ActionMenu(
    folders: List<FolderEntity>,
    onPick: (ActionType, String?) -> Unit
) {
    var folderPickerFor by remember { mutableStateOf<ActionType?>(null) }

    if (folderPickerFor == null) {
        ActionType.entries.forEach { type ->
            DropdownMenuItem(
                text = { Text(type.label) },
                onClick = {
                    if (type.needsTargetFolder) folderPickerFor = type
                    else onPick(type, null)
                }
            )
        }
    } else {
        val type = folderPickerFor!!
        val candidates = if (type == ActionType.MOVE_TO_LOCAL || type == ActionType.COPY_TO_LOCAL) {
            folders.filter { it.isLocal }
        } else {
            folders.filter { !it.isLocal }
        }
        if (candidates.isEmpty()) {
            DropdownMenuItem(
                text = { Text("No matching folder — create one first") },
                onClick = { folderPickerFor = null }
            )
        }
        candidates.forEach { folder ->
            DropdownMenuItem(
                text = { Text(folder.displayName) },
                onClick = {
                    val arg = if (folder.isLocal) SyncManager.localName(folder.path) else folder.path
                    folderPickerFor = null
                    onPick(type, arg)
                }
            )
        }
    }
}

@Composable
private fun ConditionCard(
    condition: RuleConditionEntity,
    onChange: (RuleConditionEntity) -> Unit,
    onRemove: () -> Unit
) {
    val field = runCatching { RuleField.valueOf(condition.field) }.getOrDefault(RuleField.SUBJECT)
    val operator = runCatching { RuleOperator.valueOf(condition.operator) }
        .getOrDefault(RuleOperator.CONTAINS)
    val regexValid = operator != RuleOperator.REGEX ||
        condition.value.isBlank() || RuleMatcher.isValidRegex(condition.value)

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScopeDropdown(
                    label = field.label,
                    options = RuleField.entries.map { it.label to it },
                    onPick = { onChange(condition.copy(field = it.name)) },
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Remove condition") }
            }
            if (field == RuleField.HEADER) {
                OutlinedTextField(
                    value = condition.headerName.orEmpty(),
                    onValueChange = { onChange(condition.copy(headerName = it)) },
                    label = { Text("Header name") },
                    placeholder = { Text("x-github-event") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            }
            ScopeDropdown(
                label = (if (condition.negate) "does not " else "") + operator.label,
                options = RuleOperator.entries.map { it.label to it },
                onPick = { onChange(condition.copy(operator = it.name)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = condition.value,
                onValueChange = { onChange(condition.copy(value = it)) },
                label = { Text("Value") },
                isError = !regexValid,
                supportingText = if (!regexValid) {
                    { Text("Not a valid regular expression") }
                } else null,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = condition.negate,
                    onClick = { onChange(condition.copy(negate = !condition.negate)) },
                    label = { Text("Invert") }
                )
                Text("  ")
                FilterChip(
                    selected = condition.caseSensitive,
                    onClick = { onChange(condition.copy(caseSensitive = !condition.caseSensitive)) },
                    label = { Text("Case sensitive") }
                )
            }
        }
    }
}
