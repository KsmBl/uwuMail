package de.uwumail.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rules") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) { Icon(Icons.Default.Add, "New rule") }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (rules.isEmpty()) {
                item {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            "No rules yet",
                            "Select a few similar mails in the message list and tap the label " +
                                "icon — uwuMail works out what they have in common for you."
                        )
                    }
                }
            }

            items(rules, key = { it.rule.id }) { entry ->
                ListItem(
                    modifier = Modifier.clickable { onEdit(entry.rule.id) },
                    headlineContent = { Text(entry.rule.name) },
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
                                    "matched ${entry.rule.matchCount}× · last " +
                                        formatListDate(entry.rule.lastMatchedAt ?: 0),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    leadingContent = {
                        Switch(
                            checked = entry.rule.enabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    container.db.ruleDao()
                                        .updateRule(entry.rule.copy(enabled = enabled))
                                }
                            }
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { pendingDelete = entry }) {
                            Icon(
                                Icons.Default.Delete,
                                "Delete rule",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
                HorizontalDivider()
            }

            if (log.isNotEmpty()) {
                item { SectionHeader("Recent activity") }
                items(log, key = { it.id }) { entry ->
                    ListItem(
                        headlineContent = {
                            Text(entry.subject.ifBlank { "(no subject)" }, maxLines = 1)
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
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = "Delete \"${entry.rule.name}\"?",
            message = "Mail this rule already acted on is not restored.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { scope.launch { container.db.ruleDao().deleteRule(entry.rule.id) } },
            onDismiss = { pendingDelete = null }
        )
    }
}

private fun describeConditions(entry: RuleWithDetails): String {
    val joiner = if (entry.rule.matchMode == "ALL") " and " else " or "
    return entry.conditions.joinToString(joiner) { condition ->
        val field = condition.headerName?.let { "header $it" } ?: condition.field.lowercase()
        val operator = runCatching {
            de.uwumail.core.RuleOperator.valueOf(condition.operator).label
        }.getOrDefault(condition.operator.lowercase())
        val negation = if (condition.negate) "not " else ""
        "$field $negation$operator \"${condition.value.take(40)}\""
    }.ifBlank { "no conditions" }
}

private fun describeActions(entry: RuleWithDetails): String =
    entry.actions.joinToString(", ") { action ->
        val label = runCatching { ActionType.valueOf(action.type).label }
            .getOrDefault(action.type)
        action.stringArg?.let { "$label → $it" } ?: label
    }.ifBlank { "no actions" }
