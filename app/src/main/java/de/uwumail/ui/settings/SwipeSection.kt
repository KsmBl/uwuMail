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
import androidx.compose.ui.unit.dp
import de.uwumail.core.SwipeAction
import de.uwumail.ui.LocalAppContainer

/** What swiping a message in the list does, chosen separately for each direction. */
@Composable
fun SwipeSection() {
    val container = LocalAppContainer.current
    val settings by container.settings.state.collectAsState()

    SwipeActionRow(
        title = "Swipe right",
        icon = Icons.AutoMirrored.Filled.ArrowForward,
        selected = settings.swipeRight,
        onPick = { action -> container.settings.update { it.copy(swipeRight = action) } }
    )
    SwipeActionRow(
        title = "Swipe left",
        icon = Icons.AutoMirrored.Filled.ArrowBack,
        selected = settings.swipeLeft,
        onPick = { action -> container.settings.update { it.copy(swipeLeft = action) } }
    )

    Text(
        "Set a direction to \"Nothing\" and it stops swiping altogether. Swiping is " +
            "off while messages are selected.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

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
            supportingContent = { Text(selected.label) }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SwipeAction.entries.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            action.label,
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
