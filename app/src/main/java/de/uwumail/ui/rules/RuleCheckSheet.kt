package de.uwumail.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.uwumail.R
import de.uwumail.core.RuleField
import de.uwumail.core.RuleOperator
import de.uwumail.rules.ConditionCheck
import de.uwumail.rules.RuleCheck
import de.uwumail.rules.RuleVerdict

/**
 * Why the rules did, or did not, act on this message.
 *
 * The rule screens answer "how much does this catch", which is the question
 * about a rule that is too broad. This is the opposite one, and the one people
 * actually hit: here is a message the rule was written for, and nothing
 * happened to it. The reasons are all invisible from the rule's own screen —
 * a scope pointing at another folder, a value one character out, a
 * case-sensitive test, or an earlier rule that stops processing and meant this
 * one was never reached.
 *
 * So every condition is shown with what it was looking for beside what the
 * message actually has, and the rules that never ran say why they did not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleCheckSheet(
    checks: List<RuleCheck>,
    loading: Boolean,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        val matched = checks.count { it.matched }
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(
                stringResource(R.string.rule_check_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                when {
                    loading -> stringResource(R.string.rule_check_working)
                    checks.isEmpty() -> stringResource(R.string.rule_check_no_rules)
                    matched == 0 -> stringResource(R.string.rule_check_none_matched)
                    else -> stringResource(R.string.rule_check_matched, matched)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            if (loading) CircularProgressIndicator(Modifier.padding(vertical = 16.dp))
        }

        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            items(checks, key = { it.rule.id }) { check ->
                RuleCheckRow(check)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun RuleCheckRow(check: RuleCheck) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (check.matched) Icons.Default.CheckCircle else Icons.Default.Cancel,
                contentDescription = null,
                tint = if (check.matched) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "  " + check.rule.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            verdictText(check),
            style = MaterialTheme.typography.bodySmall,
            color = if (check.matched) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, start = 24.dp)
        )
        check.conditions.forEach { condition ->
            ConditionRow(condition, Modifier.padding(start = 24.dp, top = 6.dp))
        }
    }
}

/**
 * One test, and the message's own value under it.
 *
 * The value is the point. A rule not firing is nearly always a value that
 * looks like the one being tested for and is not it — a trailing space, a
 * different sender for the same newsletter, a subject with a soft hyphen in
 * it — and none of that is visible until the two are put side by side.
 */
@Composable
private fun ConditionRow(check: ConditionCheck, modifier: Modifier = Modifier) {
    val field = runCatching { RuleField.valueOf(check.condition.field) }.getOrNull()
    val operator = runCatching { RuleOperator.valueOf(check.condition.operator) }.getOrNull()
    val tint = if (check.matched) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.error

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                if (check.matched) "✓" else "✗",
                style = MaterialTheme.typography.bodySmall,
                color = tint
            )
            Column(Modifier.padding(start = 8.dp)) {
                Text(
                    buildString {
                        append(field?.let { stringResourceOf(it) } ?: check.condition.field)
                        if (check.condition.field == RuleField.HEADER.name) {
                            check.condition.headerName?.let { append(" \"$it\"") }
                        }
                        append(" ")
                        if (check.condition.negate) {
                            append(stringResource(R.string.rule_check_not)).append(" ")
                        }
                        append(operator?.let { stringResourceOf(it) } ?: check.condition.operator)
                        append(" \"").append(check.condition.value).append("\"")
                        if (check.condition.caseSensitive) {
                            append(" (").append(stringResource(R.string.rule_case_sensitive))
                                .append(")")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    if (check.actual.isEmpty()) {
                        stringResource(R.string.rule_check_empty)
                    } else {
                        stringResource(
                            R.string.rule_check_actual,
                            check.actual.joinToString(", ") { it.take(ACTUAL_LIMIT) }
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun verdictText(check: RuleCheck): String = when (check.verdict) {
    RuleVerdict.MATCHED -> stringResource(R.string.rule_check_acted)
    RuleVerdict.DISABLED -> stringResource(R.string.rule_check_disabled)
    RuleVerdict.OTHER_ACCOUNT -> stringResource(R.string.rule_check_other_account)
    RuleVerdict.OTHER_FOLDER -> stringResource(
        R.string.rule_check_other_folder, check.rule.folderPath.orEmpty()
    )
    RuleVerdict.NO_CONDITIONS -> stringResource(R.string.rule_check_no_conditions)
    RuleVerdict.NOT_REACHED -> stringResource(R.string.rule_check_not_reached)
    RuleVerdict.CONDITIONS_NOT_MET ->
        if (check.requireAll) stringResource(R.string.rule_check_needs_all)
        else stringResource(R.string.rule_check_needs_any)
}

@Composable
private fun stringResourceOf(field: RuleField): String = stringResource(field.label)

@Composable
private fun stringResourceOf(operator: RuleOperator): String = stringResource(operator.label)

/** Enough of a value to recognise it; a body would otherwise fill the sheet. */
private const val ACTUAL_LIMIT = 200
