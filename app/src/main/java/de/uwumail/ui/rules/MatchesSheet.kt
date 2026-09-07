package de.uwumail.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.uwumail.R
import de.uwumail.ui.common.formatListDate

/**
 * One message a draft rule would catch, already put into words.
 *
 * The folder is resolved where the database is, so the sheet stays a list of
 * strings rather than something that has to look anything up while it draws.
 */
data class MatchedMail(
    val id: Long,
    val subject: String,
    val from: String,
    val folder: String,
    val receivedAt: Long,
    /** True for the messages the wizard was started from, which are always caught. */
    val isSample: Boolean = false
)

/**
 * What a rule catches, across every folder.
 *
 * Both the wizard and the editor could say "matches 312 of 1,240" and no more,
 * which is a number to be trusted rather than a thing to be checked — and the
 * one question anybody actually has about a rule is *which* mail it takes. The
 * samples the wizard started from are marked, so it is clear at a glance which
 * of the list was asked for and which the rule went and found on its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchesSheet(
    matches: List<MatchedMail>,
    scanned: Int,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                pluralStringResource(R.plurals.matches_title, matches.size, matches.size),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.matches_scanned, scanned),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        if (matches.isEmpty()) {
            Text(
                stringResource(R.string.matches_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            return@ModalBottomSheet
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(matches, key = { it.id }) { mail ->
                ListItem(
                    overlineContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                mail.folder,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (mail.isSample) {
                                Text(
                                    stringResource(R.string.matches_picked),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    headlineContent = {
                        Text(
                            mail.subject.ifBlank { stringResource(R.string.no_subject) },
                            maxLines = 1
                        )
                    },
                    supportingContent = {
                        Text(
                            mail.from.ifBlank { stringResource(R.string.unknown_sender) },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                    },
                    trailingContent = {
                        Text(
                            formatListDate(mail.receivedAt),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}
