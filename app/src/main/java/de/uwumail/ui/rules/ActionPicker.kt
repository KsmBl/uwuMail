package de.uwumail.ui.rules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uwumail.R
import de.uwumail.core.ActionType
import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.FolderEntity
import de.uwumail.sync.SyncManager

/**
 * Picks a rule action, one question at a time.
 *
 * The actions that need a folder used to open straight onto every folder of
 * every account in one flat list, where the same handful of names — Archive,
 * Spam, Sent — appears once per mailbox with nothing to say which is which.
 * With more than one account connected the mailbox is asked for first.
 *
 * Which mailbox is browsed does not narrow the rule. The folder is stored as
 * its path, and a rule that runs on every account files mail into that path
 * wherever it exists; picking a mailbox here is a way of finding the path.
 * Whether the rule runs on one account or all of them is a separate question,
 * asked under *Applies to*.
 */
@Composable
fun ActionPickerMenu(
    types: List<ActionType>,
    folders: List<FolderEntity>,
    accounts: List<AccountEntity>,
    onPick: (ActionType, String?) -> Unit
) {
    var pickingFor by remember { mutableStateOf<ActionType?>(null) }
    var mailbox by remember { mutableStateOf<Long?>(null) }

    val type = pickingFor
    if (type == null) {
        types.forEach { entry ->
            val name = stringResource(entry.label)
            DropdownMenuItem(
                // The ellipsis is the promise that this one asks something else
                // before it is added, rather than being added on the spot.
                text = { Text(if (entry.needsTargetFolder) "$name…" else name) },
                onClick = {
                    if (entry.needsTargetFolder) pickingFor = entry else onPick(entry, null)
                }
            )
        }
        return
    }

    val candidates = folders.filter { it.isLocal == type.wantsLocalFolder }
    // Only the mailboxes that actually have somewhere to put the mail. On a
    // single-account phone this leaves one, and the question is not asked.
    val mailboxes = accounts.filter { account -> candidates.any { it.accountId == account.id } }
    val chosen = mailbox ?: mailboxes.singleOrNull()?.id

    Step(
        title = stringResource(type.label),
        subtitle = if (chosen == null) stringResource(R.string.rule_which_mailbox)
        else stringResource(R.string.rule_which_folder),
        onBack = {
            if (chosen != null && mailbox != null) mailbox = null else pickingFor = null
        }
    )

    if (chosen == null) {
        mailboxes.forEach { account ->
            DropdownMenuItem(
                text = { Text(account.email) },
                onClick = { mailbox = account.id }
            )
        }
        return
    }

    val available = candidates.filter { it.accountId == chosen }
    if (available.isEmpty()) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.rule_no_folder)) },
            onClick = { pickingFor = null; mailbox = null }
        )
    }
    available.forEach { folder ->
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    if (folder.isLocal) Icons.Default.PhoneAndroid else Icons.Default.Folder,
                    contentDescription = null
                )
            },
            text = { Text(folder.displayName) },
            onClick = {
                pickingFor = null
                mailbox = null
                onPick(
                    type,
                    if (folder.isLocal) SyncManager.localName(folder.path) else folder.path
                )
            }
        )
    }
}

/** Says which question is being answered, and offers the way back out of it. */
@Composable
private fun Step(title: String, subtitle: String, onBack: () -> Unit) {
    DropdownMenuItem(
        leadingIcon = {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
        },
        text = {
            Column {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        onClick = onBack
    )
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}
