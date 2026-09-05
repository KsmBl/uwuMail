package de.uwumail.ui.mail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.uwumail.mail.TrackingParams

/**
 * Asks whether a link should be opened with its tracking parameters removed.
 *
 * Both answers open the link — the question is only which version — so the
 * clean one is the confirm button and the original is the dismissive one.
 */
@Composable
fun TrackingLinkDialog(
    url: String,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val stripped = TrackingParams.strip(url)
    val parameters = TrackingParams.findIn(url)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Link, contentDescription = null) },
        title = { Text("Remove tracking?") },
        text = {
            Column {
                Text(
                    "This link to ${TrackingParams.hostOf(url)} carries " +
                        "${parameters.size} tracking parameter" +
                        (if (parameters.size == 1) "" else "s") + ": " +
                        parameters.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    stripped,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onOpen(stripped) }) { Text("Open cleaned") }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss(); onOpen(url) }) { Text("Open original") }
        }
    )
}
