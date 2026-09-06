package de.uwumail.ui.mail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Unsubscribe
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.uwumail.R
import de.uwumail.mail.UnsubscribeTarget

/**
 * The strip above a message body offering one action.
 *
 * Deliberately not a Card: these sit edge to edge directly under the header so
 * they read as part of the message chrome rather than as content.
 */
@Composable
fun MessageBanner(
    icon: ImageVector,
    text: String,
    actionLabel: String,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
    onAction: () -> Unit
) {
    Surface(color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(start = 12.dp)
            )
            secondaryLabel?.let {
                TextButton(onClick = onSecondary) {
                    Text(it, color = content, style = MaterialTheme.typography.labelLarge)
                }
            }
            TextButton(onClick = onAction) {
                Text(
                    actionLabel,
                    color = content,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
fun UnsubscribeBanner(target: UnsubscribeTarget, onUnsubscribe: () -> Unit) {
    MessageBanner(
        icon = Icons.Default.Unsubscribe,
        text = when {
            target.oneClick -> stringResource(R.string.unsub_one_click)
            target.isMailto -> stringResource(R.string.unsub_mailto)
            target.fromBody -> stringResource(R.string.unsub_body)
            else -> stringResource(R.string.unsub_header)
        },
        actionLabel = stringResource(R.string.unsubscribe),
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer,
        onAction = onUnsubscribe
    )
}

@Composable
fun BlockedImagesBanner(blockedCount: Int, onShow: () -> Unit) {
    MessageBanner(
        icon = Icons.Default.Image,
        text = if (blockedCount > 0) {
            "$blockedCount remote image${if (blockedCount == 1) "" else "s"} blocked. " +
                "Loading them tells the sender you opened this."
        } else {
            "Remote images are blocked. Loading them tells the sender you opened this."
        },
        actionLabel = stringResource(R.string.show_images),
        onAction = onShow
    )
}
