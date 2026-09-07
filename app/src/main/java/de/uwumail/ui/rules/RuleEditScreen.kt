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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.ui.res.pluralStringResource
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uwumail.R
import de.uwumail.core.ActionType
import de.uwumail.core.MatchMode
import de.uwumail.core.RuleField
import de.uwumail.core.RuleOperator
import de.uwumail.data.db.RuleConditionEntity
import de.uwumail.rules.RuleMatcher
import de.uwumail.ui.common.SectionHeader
import de.uwumail.ui.containerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditScreen(ruleId: Long, onBack: () -> Unit) {
    val viewModel = containerViewModel(key = "rule-$ruleId") { RuleEditViewModel(it, ruleId) }
    val state by viewModel.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    var addActionOpen by remember { mutableStateOf(false) }
    var showMatches by remember { mutableStateOf(false) }

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
            Column {
            TopAppBar(
                title = { Text(if (ruleId > 0) stringResource(R.string.rule_edit) else stringResource(R.string.rule_new)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (state.saving) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
                    } else {
                        IconButton(onClick = viewModel::save, enabled = state.canSave) {
                            Icon(Icons.Default.Save, stringResource(R.string.save))
                        }
                    }
                }
            )
            if (state.applying || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                label = { Text(stringResource(R.string.rule_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.rule_enabled), Modifier.weight(1f))
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
                    stringResource(R.string.rule_stop_after),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = state.stopProcessing,
                    onCheckedChange = { v -> viewModel.update { it.copy(stopProcessing = v) } }
                )
            }

            SectionHeader(stringResource(R.string.rule_applies_to))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScopeDropdown(
                    label = state.accounts.firstOrNull { it.id == state.accountId }?.displayName
                        ?: stringResource(R.string.rule_all_accounts),
                    options = listOf<Pair<String, Long?>>(stringResource(R.string.rule_all_accounts) to null) +
                        state.accounts.map { it.displayName to it.id },
                    onPick = { value -> viewModel.update { it.copy(accountId = value, folderPath = null) } },
                    modifier = Modifier.weight(1f)
                )
                ScopeDropdown(
                    label = state.folderPath ?: stringResource(R.string.rule_all_folders),
                    options = listOf<Pair<String, String?>>(stringResource(R.string.rule_all_folders) to null) +
                        state.foldersForScope().map { it.displayName to it.path },
                    onPick = { value -> viewModel.update { it.copy(folderPath = value) } },
                    modifier = Modifier.weight(1f)
                )
            }

            SectionHeader(stringResource(R.string.rule_conditions))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MatchMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.matchMode == mode,
                        onClick = { viewModel.update { it.copy(matchMode = mode) } },
                        label = { Text(if (mode == MatchMode.ALL) stringResource(R.string.rule_match_all_short) else stringResource(R.string.rule_match_any_short)) }
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
                Text(" " + stringResource(R.string.add_condition))
            }

            SectionHeader(stringResource(R.string.rule_then))
            state.actions.forEachIndexed { index, action ->
                val type = runCatching { ActionType.valueOf(action.type) }.getOrNull()
                AssistChip(
                    onClick = { viewModel.removeAction(index) },
                    label = {
                        val name = type?.let { stringResource(it.label) } ?: action.type
                        Text(action.stringArg?.let { "$name → $it" } ?: name)
                    },
                    trailingIcon = { Icon(Icons.Default.Close, stringResource(R.string.remove)) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = { addActionOpen = true }) {
                    Icon(Icons.Default.Add, null)
                    Text(" " + stringResource(R.string.add_action))
                }
                DropdownMenu(
                    expanded = addActionOpen,
                    onDismissRequest = { addActionOpen = false }
                ) {
                    ActionPickerMenu(
                        types = ActionType.entries,
                        folders = state.foldersForScope(),
                        accounts = state.accounts,
                        onPick = { type, arg ->
                            addActionOpen = false
                            viewModel.addAction(type, arg)
                        }
                    )
                }
            }

            SectionHeader(stringResource(R.string.rule_before_save))
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
                    Text("  " + stringResource(R.string.rule_test))
                }
                state.previewCount?.let { count ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                pluralStringResource(
                                    R.plurals.rule_matches_count, count, count, state.previewTotal
                                ),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (count == 0) {
                                    stringResource(R.string.rule_nothing_matched)
                                } else {
                                    stringResource(R.string.rule_check_before)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (count > 0) {
                                TextButton(
                                    onClick = { showMatches = true },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Icon(Icons.Default.ListAlt, null)
                                    Text("  " + stringResource(R.string.matches_show))
                                }
                            }
                        }
                    }
                }
                if (ruleId > 0) {
                    FilledTonalButton(
                        onClick = viewModel::applyToExistingMail,
                        enabled = !state.applying,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.applying) {
                            CircularProgressIndicator(Modifier.size(16.dp))
                            Text("  " + stringResource(R.string.applying))
                        } else {
                            Text(stringResource(R.string.rule_apply_existing))
                        }
                    }
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }

    if (showMatches) {
        MatchesSheet(
            matches = state.matches,
            scanned = state.previewTotal ?: 0,
            onDismiss = { showMatches = false }
        )
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
                    label = stringResource(field.label),
                    options = RuleField.entries.map { stringResource(it.label) to it },
                    onPick = { onChange(condition.copy(field = it.name)) },
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRemove) { Icon(Icons.Default.Close, stringResource(R.string.rule_remove_condition)) }
            }
            if (field == RuleField.HEADER) {
                OutlinedTextField(
                    value = condition.headerName.orEmpty(),
                    onValueChange = { onChange(condition.copy(headerName = it)) },
                    label = { Text(stringResource(R.string.rule_header_name)) },
                    placeholder = { Text("x-github-event") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            }
            ScopeDropdown(
                label = stringResource(operator.label).let {
                    if (condition.negate) stringResource(R.string.condition_negated, it) else it
                },
                options = RuleOperator.entries.map { stringResource(it.label) to it },
                onPick = { onChange(condition.copy(operator = it.name)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = condition.value,
                onValueChange = { onChange(condition.copy(value = it)) },
                label = { Text(stringResource(R.string.rule_value)) },
                isError = !regexValid,
                supportingText = if (!regexValid) {
                    { Text(stringResource(R.string.rule_bad_regex)) }
                } else null,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = condition.negate,
                    onClick = { onChange(condition.copy(negate = !condition.negate)) },
                    label = { Text(stringResource(R.string.rule_negate)) }
                )
                Text("  ")
                FilterChip(
                    selected = condition.caseSensitive,
                    onClick = { onChange(condition.copy(caseSensitive = !condition.caseSensitive)) },
                    label = { Text(stringResource(R.string.rule_case_sensitive)) }
                )
            }
        }
    }
}
