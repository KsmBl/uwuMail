package de.uwumail.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import de.uwumail.core.ActionType
import de.uwumail.data.db.RuleWithDetails
import de.uwumail.ui.LocalAppContainer
import de.uwumail.ui.common.ConfirmDialog
import de.uwumail.ui.common.EmptyState
import de.uwumail.ui.common.SectionHeader
import de.uwumail.ui.common.formatListDate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onCreate: () -> Unit
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val rules by container.db.ruleDao().observeAll().collectAsState(initial = emptyList())
    val log by container.db.ruleDao().observeLog(40).collectAsState(initial = emptyList())
    var pendingDelete by remember { mutableStateOf<RuleWithDetails?>(null) }
    var tab by remember { mutableStateOf(0) }
    val copySuffix = stringResource(R.string.rules_copy_suffix)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rules_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) { Icon(Icons.Default.Add, stringResource(R.string.new_rule)) }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // The log used to sit under the rules in the same scroll, so forty
            // entries of history buried the handful of things anyone came here
            // to change. They answer different questions and now sit apart.
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text(stringResource(R.string.rules_tab_rules)) }
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(stringResource(R.string.rules_tab_activity)) }
                )
            }

            if (tab == 1) {
                if (log.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            stringResource(R.string.rules_log_none),
                            stringResource(R.string.rules_log_none_hint)
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(log, key = { it.id }) { entry ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        entry.subject.ifBlank { stringResource(R.string.no_subject) },
                                        maxLines = 1
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        "${entry.ruleName} → ${entry.actionsTaken}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                trailingContent = {
                                    Text(
                                        formatListDate(entry.at),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                            HorizontalDivider(thickness = 0.5.dp)
                        }
                    }
                }
                return@Column
            }

            if (rules.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        stringResource(R.string.rules_none),
                        stringResource(R.string.rules_none_hint)
                    )
                }
                return@Column
            }

            if (rules.size > 1) {
                Text(
                    stringResource(R.string.rules_order_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(rules, key = { _, it -> it.rule.id }) { index, entry ->
                    RuleRow(
                        entry = entry,
                        position = index + 1,
                        canMoveUp = index > 0,
                        canMoveDown = index < rules.lastIndex,
                        showOrder = rules.size > 1,
                        onOpen = { onEdit(entry.rule.id) },
                        onToggle = { enabled ->
                            scope.launch {
                                container.db.ruleDao()
                                    .updateRule(entry.rule.copy(enabled = enabled))
                            }
                        },
                        onMove = { delta ->
                            scope.launch {
                                val reordered = rules.map { it.rule }.toMutableList()
                                reordered.add(index + delta, reordered.removeAt(index))
                                container.db.ruleDao().renumber(reordered)
                            }
                        },
                        onDuplicate = {
                            scope.launch { container.db.ruleDao().duplicate(entry, copySuffix) }
                        },
                        onDelete = { pendingDelete = entry }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.rules_delete_q, entry.rule.name),
            message = stringResource(R.string.rules_delete_body),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = { scope.launch { container.db.ruleDao().deleteRule(entry.rule.id) } },
            onDismiss = { pendingDelete = null }
        )
    }
}

/**
 * One rule, as a line that says what it does and where it sits.
 *
 * The number is the order it runs in, which is the thing that explains a rule
 * misbehaving more often than the rule itself does: an earlier one that stops
 * processing means a later one never runs at all.
 */
@Composable
private fun RuleRow(
    entry: RuleWithDetails,
    position: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    showOrder: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showOrder) {
                    Text(
                        position.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Switch(checked = entry.rule.enabled, onCheckedChange = onToggle)
            }
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.rule.name, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (entry.rule.stopProcessing) {
                    Text(
                        "  " + stringResource(R.string.rules_stops_here),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        supportingContent = {
            Column {
                Text(
                    describeConditions(entry),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2
                )
                Text(
                    describeActions(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (entry.rule.matchCount > 0) {
                    Text(
                        stringResource(
                            R.string.rules_matched_summary,
                            entry.rule.matchCount,
                            formatListDate(entry.rule.lastMatchedAt ?: 0)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.more))
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
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rules_duplicate)) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = { menu = false; onDuplicate() }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.rules_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
        }
    )
}

@Composable
private fun describeConditions(entry: RuleWithDetails): String {
    val joiner = if (entry.rule.matchMode == "ALL") {
        stringResource(R.string.rule_join_and)
    } else {
        stringResource(R.string.rule_join_or)
    }
    val negation = stringResource(R.string.rule_not)
    val headerLabel = stringResource(R.string.rule_header_prefix)
    val none = stringResource(R.string.rule_no_conditions)
    val parts = entry.conditions.map { condition ->
        // The label the editor uses, in the reader's own language, rather than
        // the enum constant lowercased — "to_or_cc" is not a phrase.
        val field = condition.headerName?.let { "$headerLabel $it" }
            ?: runCatching {
                stringResource(de.uwumail.core.RuleField.valueOf(condition.field).label)
            }.getOrDefault(condition.field.lowercase())
        val operator = runCatching {
            stringResource(de.uwumail.core.RuleOperator.valueOf(condition.operator).label)
        }.getOrDefault(condition.operator.lowercase())
        val not = if (condition.negate) "$negation " else ""
        "$field $not$operator \"${condition.value.take(40)}\""
    }
    return parts.joinToString(joiner).ifBlank { none }
}

@Composable
private fun describeActions(entry: RuleWithDetails): String {
    val none = stringResource(R.string.rule_no_actions)
    val labels = entry.actions.map { action ->
        val type = runCatching { ActionType.valueOf(action.type) }.getOrNull()
        val label = type?.let { stringResource(it.label) } ?: action.type
        action.stringArg?.let { "$label → $it" } ?: label
    }
    return labels.joinToString(", ").ifBlank { none }
}
