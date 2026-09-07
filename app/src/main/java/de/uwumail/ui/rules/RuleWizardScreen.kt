package de.uwumail.ui.rules

import android.content.Intent
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import de.uwumail.core.WizardLog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import de.uwumail.R
import de.uwumail.core.ActionType
import de.uwumail.rules.Suggestion
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
    var showMatches by remember { mutableStateOf(false) }
    // The wizard has been seen to sit on its spinner on a device I cannot
    // reproduce it on, so the trace is on screen as well as in the file.
    val log by WizardLog.lines.collectAsState()
    val context = LocalContext.current
    // The trace is always written; it is only put on screen once the analysis
    // has taken long enough to be worth explaining. A wizard that answers in
    // half a second does not need to show its working.
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(state.analysing) {
        slow = false
        if (state.analysing) {
            delay(SLOW_ENOUGH_TO_EXPLAIN)
            slow = true
        }
    }

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
                    if (slow || state.error != null) {
                        IconButton(onClick = { shareLog(context) }) {
                            Icon(Icons.Default.Share, stringResource(R.string.wizard_log_share))
                        }
                    }
                    IconButton(
                        onClick = { viewModel.save(onSaved) },
                        enabled = state.canSave
                    ) { Icon(Icons.Default.Save, stringResource(R.string.wizard_save)) }
                }
            )
        }
    ) { padding ->
        state.error?.let { failure ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                EmptyState(
                    title = stringResource(R.string.wizard_failed),
                    subtitle = failure
                )
                WizardLogView(log, Modifier.weight(1f))
            }
            return@Scaffold
        }
        if (state.analysing) {
            Column(
                Modifier.padding(padding).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 32.dp)) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Text(
                            stringResource(R.string.wizard_looking),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                if (slow) WizardLogView(log, Modifier.weight(1f))
            }
            return@Scaffold
        }

        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            pluralStringResource(
                                R.plurals.wizard_selected,
                                state.samples.size,
                                state.samples.size
                            ),
                            fontWeight = FontWeight.SemiBold
                        )
                        state.samples.take(SAMPLES_SHOWN).forEach { message ->
                            Text(
                                "· " + message.subject.take(70)
                                    .ifBlank { stringResource(R.string.no_subject) },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                        if (state.samples.size > SAMPLES_SHOWN) {
                            Text(
                                "· " + stringResource(
                                    R.string.wizard_and_more,
                                    state.samples.size - SAMPLES_SHOWN
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // With one mailbox connected the question has one answer, so it is
            // not asked. The editor still offers it for a rule that should stay
            // on this account once a second one is added.
            if (state.accounts.size > 1) {
                item { SectionHeader(stringResource(R.string.rule_applies_to)) }
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        AccountScopeDropdown(
                            accounts = state.accounts,
                            selected = state.accountId,
                            onPick = viewModel::setAccountScope
                        )
                        Text(
                            if (state.accountId == null) {
                                stringResource(R.string.wizard_scope_all)
                            } else {
                                stringResource(R.string.wizard_scope_one)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
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
                            // A count is a number to be trusted; the list is a
                            // thing to be checked, which is what anybody
                            // actually wants before saving a rule.
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

            item { SectionHeader(stringResource(R.string.wizard_then)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    state.actions.forEachIndexed { index, action ->
                        AssistChip(
                            onClick = { viewModel.removeAction(index) },
                            label = {
                                val name = stringResource(action.type.label)
                                Text(action.arg?.let { "$name → $it" } ?: name)
                            },
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
                        ActionPickerMenu(
                            types = WIZARD_ACTIONS,
                            folders = state.foldersForScope(),
                            accounts = state.accounts,
                            onPick = { type, arg ->
                                actionMenu = false
                                viewModel.addAction(type, arg)
                            }
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

    if (showMatches) {
        MatchesSheet(
            matches = state.matches,
            scanned = state.scanned,
            onDismiss = { showMatches = false }
        )
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
            Text(say(suggestion.label), style = MaterialTheme.typography.bodyLarge)
            Text(
                say(suggestion.detail),
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

/** Which mailboxes the rule runs on: one of them, or all of them. */
@Composable
private fun AccountScopeDropdown(
    accounts: List<de.uwumail.data.db.AccountEntity>,
    selected: Long?,
    onPick: (Long?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val label = accounts.firstOrNull { it.id == selected }?.email
        ?: stringResource(R.string.rule_all_accounts)
    Column {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label, maxLines = 1, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rule_all_accounts)) },
                onClick = { open = false; onPick(null) }
            )
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.email) },
                    onClick = { open = false; onPick(account.id) }
                )
            }
        }
    }
}

/**
 * What the wizard offers to do with the mail.
 *
 * A shorter list than the editor's, in the order somebody reaches for it after
 * selecting a few messages: the reason for building a rule from mail in front
 * of you is almost always that you did not want to be told about it.
 */
private val WIZARD_ACTIONS = listOf(
    ActionType.SUPPRESS_NOTIFICATION,
    ActionType.NOTIFY_SILENT,
    ActionType.MARK_READ,
    ActionType.ARCHIVE,
    ActionType.MOVE_TO_TRASH,
    ActionType.FLAG,
    ActionType.DOWNLOAD,
    ActionType.MOVE_TO_FOLDER,
    ActionType.COPY_TO_FOLDER,
    ActionType.MOVE_TO_LOCAL,
    ActionType.DELETE_PERMANENTLY
)

/**
 * The trace, newest at the bottom, kept scrolled there. It is on screen and
 * not only in the file because the phone this happens on is not always the
 * phone with a cable attached.
 */
@Composable
private fun WizardLogView(log: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.scrollToItem(log.lastIndex)
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        SectionHeader(stringResource(R.string.wizard_log_title))
        Text(
            stringResource(R.string.wizard_log_path, WizardLog.path()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
            items(log) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 3
                )
            }
        }
    }
}

/** How long the spinner may spin before it starts explaining itself. */
private const val SLOW_ENOUGH_TO_EXPLAIN = 5000L

/** How many of the selected subjects are listed before the rest are counted. */
private const val SAMPLES_SHOWN = 3

private fun shareLog(context: android.content.Context) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "uwuMail rule wizard log")
        putExtra(Intent.EXTRA_TEXT, WizardLog.text())
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.wizard_log_share))
        )
    }
}

/** Turns a suggester [Phrase] into words in the phone's own language. */
@Composable
private fun say(phrase: de.uwumail.rules.Phrase): String =
    stringResource(phrase.id, *phrase.args.toTypedArray())
