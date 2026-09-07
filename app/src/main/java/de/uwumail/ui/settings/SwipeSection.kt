package de.uwumail.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.SwipeRight
import androidx.compose.material3.Slider
import androidx.compose.ui.Alignment
import de.uwumail.data.settings.AppSettings
import kotlin.math.roundToInt
import de.uwumail.R
import de.uwumail.core.SwipeAction
import de.uwumail.ui.LocalAppContainer

/** What swiping a message in the list does, chosen separately for each direction. */
@Composable
fun SwipeSection() {
    val container = LocalAppContainer.current
    val settings by container.settings.state.collectAsState()

    SwipeActionRow(
        title = stringResource(R.string.swipe_right),
        icon = Icons.AutoMirrored.Filled.ArrowForward,
        selected = settings.swipeRight,
        onPick = { action -> container.settings.update { it.copy(swipeRight = action) } }
    )
    SwipeActionRow(
        title = stringResource(R.string.swipe_left),
        icon = Icons.AutoMirrored.Filled.ArrowBack,
        selected = settings.swipeLeft,
        onPick = { action -> container.settings.update { it.copy(swipeLeft = action) } }
    )

    SwipeDistanceRow(
        percent = settings.swipeThresholdPercent,
        onChange = { percent ->
            container.settings.update { it.copy(swipeThresholdPercent = percent) }
        }
    )

    Text(
        stringResource(R.string.swipe_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

/**
 * How far a swipe has to travel, as a share of the screen's width.
 *
 * Written back only when the finger lifts: every intermediate value of a drag
 * would otherwise be a separate write to disk, and the number under the slider
 * follows the finger regardless.
 */
@Composable
private fun SwipeDistanceRow(percent: Int, onChange: (Int) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging?.roundToInt() ?: percent

    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SwipeRight, null)
            Text(
                stringResource(R.string.swipe_distance),
                modifier = Modifier.padding(start = 16.dp).weight(1f),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                stringResource(R.string.swipe_distance_value, shown),
                style = MaterialTheme.typography.titleMedium
            )
        }
        Slider(
            value = shown.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onChange(AppSettings.clampSwipePercent(it.roundToInt())) }
                dragging = null
            },
            valueRange = AppSettings.MIN_SWIPE_PERCENT.toFloat()..
                AppSettings.MAX_SWIPE_PERCENT.toFloat(),
            // In fives: a swipe distance is a feel, not a measurement, and the
            // difference between 43% and 44% of a screen is nothing anyone can
            // aim for.
            steps = STEPS
        )
        Text(
            stringResource(R.string.swipe_distance_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Stops between the ends, so the slider lands on multiples of five: the range
 * is 10-90, which is sixteen five-point stops with the two ends excluded.
 */
private const val STEPS = 15

@Composable
private fun SwipeActionRow(
    title: String,
    icon: ImageVector,
    selected: SwipeAction,
    onPick: (SwipeAction) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.clickable { open = true },
            leadingContent = { Icon(icon, null) },
            headlineContent = { Text(title) },
            supportingContent = { Text(stringResource(selected.label)) }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SwipeAction.entries.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(action.label),
                            color = if (action == SwipeAction.DELETE) {
                                MaterialTheme.colorScheme.error
                            } else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = { open = false; onPick(action) }
                )
            }
        }
    }
}
