package de.uwumail.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uwumail.R
import de.uwumail.data.settings.AppSettings
import de.uwumail.ui.LocalAppContainer

/** Which days and hours uwuMail is allowed to check for mail on its own. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SyncWindowSection() {
    val container = LocalAppContainer.current
    val settings by container.settings.state.collectAsState()
    var editing by remember { mutableStateOf<Boundary?>(null) }

    SettingSwitch(
        title = stringResource(R.string.window_only_at),
        subtitle = if (settings.syncWindowEnabled) {
            "${AppSettings.formatTime(settings.syncStartMinutes)} to " +
                "${AppSettings.formatTime(settings.syncEndMinutes)} on " +
                daysSummary(settings)
        } else {
            stringResource(R.string.window_always)
        },
        icon = Icons.Default.Schedule,
        checked = settings.syncWindowEnabled,
        onChange = { value -> container.settings.update { it.copy(syncWindowEnabled = value) } }
    )

    if (!settings.syncWindowEnabled) return

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        OutlinedButton(onClick = { editing = Boundary.START }, modifier = Modifier.weight(1f)) {
            Text("From ${AppSettings.formatTime(settings.syncStartMinutes)}")
        }
        OutlinedButton(onClick = { editing = Boundary.END }, modifier = Modifier.weight(1f)) {
            Text("To ${AppSettings.formatTime(settings.syncEndMinutes)}")
        }
    }

    // Seven chips do not fit across a narrow phone, and the seventh falling
    // off the edge is a day that cannot be chosen at all.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        AppSettings.DAY_ORDER.forEach { day ->
            val selected = day in settings.syncDays
            FilterChip(
                selected = selected,
                onClick = {
                    container.settings.update { current ->
                        val next = if (selected) current.syncDays - day else current.syncDays + day
                        // No days at all would silently stop mail altogether;
                        // the switch above is how you turn checking off.
                        if (next.isEmpty()) current else current.copy(syncDays = next)
                    }
                },
                label = { Text(AppSettings.dayLabel(day)) }
            )
        }
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            stringResource(R.string.window_applies),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (settings.syncEndMinutes <= settings.syncStartMinutes) {
            Text(
                stringResource(
                    R.string.window_overnight,
                    AppSettings.formatTime(settings.syncStartMinutes),
                    AppSettings.formatTime(settings.syncEndMinutes)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    editing?.let { boundary ->
        TimeDialog(
            title = if (boundary == Boundary.START) stringResource(R.string.window_start) else stringResource(R.string.window_stop),
            minutes = if (boundary == Boundary.START) settings.syncStartMinutes
            else settings.syncEndMinutes,
            onConfirm = { value ->
                container.settings.update {
                    if (boundary == Boundary.START) it.copy(syncStartMinutes = value)
                    else it.copy(syncEndMinutes = value)
                }
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
}

private enum class Boundary { START, END }

@Composable
private fun daysSummary(settings: AppSettings): String = when {
    settings.syncDays.size == AppSettings.ALL_DAYS.size -> stringResource(R.string.window_every_day)
    else -> AppSettings.DAY_ORDER.filter { it in settings.syncDays }
        .joinToString(", ", transform = AppSettings::dayLabel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(
    title: String,
    minutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = minutes / 60,
        initialMinute = minutes % 60,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.window_set)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
