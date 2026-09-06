package de.uwumail.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uwumail.R
import de.uwumail.core.AppTheme
import de.uwumail.ui.LocalAppContainer

/** Which colours the app wears. */
@Composable
fun AppearanceSection() {
    val container = LocalAppContainer.current
    val settings by container.settings.state.collectAsState()
    var open by remember { mutableStateOf(false) }

    Box {
        ListItem(
            modifier = Modifier.clickable { open = true },
            leadingContent = { Icon(Icons.Default.Palette, null) },
            headlineContent = { Text(stringResource(R.string.set_theme)) },
            supportingContent = { Text(stringResource(settings.theme.label)) },
            trailingContent = { ThemeDots(settings.theme) }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AppTheme.entries.forEach { theme ->
                DropdownMenuItem(
                    text = { Text(stringResource(theme.label)) },
                    leadingIcon = { ThemeDots(theme) },
                    onClick = { open = false; container.settings.update { it.copy(theme = theme) } }
                )
            }
        }
    }
}

/** A glance at what a theme looks like, so the names do not have to carry it. */
@Composable
private fun ThemeDots(theme: AppTheme) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        swatches(theme).forEach { colour ->
            Box(Modifier.size(14.dp).clip(CircleShape).background(colour))
        }
    }
}

private fun swatches(theme: AppTheme): List<Color> = when (theme) {
    // The system's own colours are not knowable here, so this stands for them.
    AppTheme.SYSTEM -> listOf(Color(0xFF6750A4), Color(0xFFD0BCFF), Color(0xFFEFB8C8))
    AppTheme.LIGHT -> listOf(Color(0xFFFFFBFE), Color(0xFF6750A4), Color(0xFF7D5260))
    AppTheme.DARK -> listOf(Color(0xFF1C1B1F), Color(0xFFD0BCFF), Color(0xFFEFB8C8))
    AppTheme.MOCHA -> listOf(Color(0xFF1E1E2E), Color(0xFFCBA6F7), Color(0xFFF5C2E7))
}
