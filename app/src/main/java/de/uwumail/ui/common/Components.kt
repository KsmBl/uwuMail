package de.uwumail.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.FolderEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    supportingText: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        isError = isError,
        enabled = enabled,
        supportingText = supportingText?.let { { Text(it) } },
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Confirm",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun TextPromptDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmLabel: String = "Create",
    supportingText: String? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    singleLine = true,
                    supportingText = supportingText?.let { { Text(it) } }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()); onDismiss() },
                enabled = text.isNotBlank()
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun EmptyState(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        action?.invoke()
    }
}

/**
 * How a folder is named wherever more than one account is in play: the mailbox
 * it belongs to in brackets, then the folder itself. Mail can be moved between
 * accounts, so the account is the part that disambiguates — every mailbox has
 * an "Archive".
 */
fun folderLabel(folder: FolderEntity, accounts: List<AccountEntity>): String {
    val account = accounts.firstOrNull { it.id == folder.accountId }
    return if (account == null) folder.displayName
    else "[${account.email}] ${folder.displayName}"
}

/**
 * Picks a destination folder, across every account.
 *
 * Folders are grouped by mailbox with [preferredAccountId] — the one the mail
 * is in now — first, since moving within the same account is the common case
 * and moving between accounts is the deliberate one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerSheet(
    folders: List<FolderEntity>,
    accounts: List<AccountEntity>,
    title: String = "Move to",
    confirmLabel: String = "Move here",
    preferredAccountId: Long? = null,
    onPick: (FolderEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf<FolderEntity?>(null) }
    val ordered = remember(folders, accounts, preferredAccountId) {
        folders.sortedWith(
            compareBy(
                { if (it.accountId == preferredAccountId) 0 else 1 },
                { accounts.indexOfFirst { account -> account.id == it.accountId } },
                { it.accountId }
            )
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Opened at full height: the confirm button is pinned under the list,
        // and at half height it sits below the bottom of the screen where
        // there is nothing to suggest it exists.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            items(ordered, key = { it.id }) { folder ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected?.id == folder.id,
                            onClick = { selected = folder }
                        )
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    RadioButton(selected = selected?.id == folder.id, onClick = { selected = folder })
                    Icon(
                        if (folder.isLocal) Icons.Default.PhoneAndroid else Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            folderLabel(folder, accounts),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            if (folder.isLocal) "on this device" else folder.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = { selected?.let { onPick(it) } },
                enabled = selected != null
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Text("  $confirmLabel")
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dayFormat = SimpleDateFormat("d MMM", Locale.getDefault())
private val fullFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
private val absoluteFormat = SimpleDateFormat("EEE, d MMM yyyy HH:mm", Locale.getDefault())

/** Today shows a clock, this year shows a day, older shows a year. */
fun formatListDate(millis: Long): String {
    if (millis <= 0) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    return when {
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) ->
            timeFormat.format(Date(millis))
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> dayFormat.format(Date(millis))
        else -> fullFormat.format(Date(millis))
    }
}

fun formatFullDate(millis: Long): String =
    if (millis <= 0) "" else absoluteFormat.format(Date(millis))

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
}
