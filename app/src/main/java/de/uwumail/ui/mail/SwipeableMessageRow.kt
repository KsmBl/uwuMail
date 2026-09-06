package de.uwumail.ui.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import de.uwumail.core.SwipeAction

/**
 * A message row that can be swiped either way.
 *
 * Each direction does whatever the user set it to, and a direction set to
 * nothing does not swipe at all. Only the actions that take the message out of
 * the list carry the row off the screen; the rest spring back, because the row
 * is still there afterwards and pretending otherwise would leave a gap that
 * fills itself back in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableMessageRow(
    rightAction: SwipeAction,
    leftAction: SwipeAction,
    seen: Boolean,
    flagged: Boolean,
    enabled: Boolean,
    onAction: (SwipeAction) -> Unit,
    content: @Composable () -> Unit
) {
    if (!enabled || (rightAction == SwipeAction.NONE && leftAction == SwipeAction.NONE)) {
        content()
        return
    }

    val currentOnAction by rememberUpdatedState(onAction)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            val action = when (value) {
                SwipeToDismissBoxValue.StartToEnd -> rightAction
                SwipeToDismissBoxValue.EndToStart -> leftAction
                SwipeToDismissBoxValue.Settled -> SwipeAction.NONE
            }
            if (action == SwipeAction.NONE) return@rememberSwipeToDismissBoxState false
            currentOnAction(action)
            // Anything that needs an answer first has to spring back: the
            // question can still be answered with no.
            action.carriesRowAway && !action.needsConfirmation
        }
    )

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = rightAction != SwipeAction.NONE,
        enableDismissFromEndToStart = leftAction != SwipeAction.NONE,
        backgroundContent = {
            val action = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> rightAction
                SwipeToDismissBoxValue.EndToStart -> leftAction
                SwipeToDismissBoxValue.Settled -> null
            }
            if (action != null && action != SwipeAction.NONE) {
                SwipeBackground(
                    action = action,
                    seen = seen,
                    flagged = flagged,
                    fromStart = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                )
            }
        },
        content = { content() }
    )
}

@Composable
private fun SwipeBackground(
    action: SwipeAction,
    seen: Boolean,
    flagged: Boolean,
    fromStart: Boolean
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(swipeColour(action))
            .padding(horizontal = 24.dp),
        contentAlignment = if (fromStart) Alignment.CenterStart else Alignment.CenterEnd
    ) {
        Icon(
            imageVector = swipeIcon(action, seen, flagged),
            contentDescription = action.label,
            tint = swipeInk(action),
            modifier = Modifier.size(24.dp)
        )
    }
}

/** The icon shows what is about to happen, so the toggles show their outcome. */
private fun swipeIcon(action: SwipeAction, seen: Boolean, flagged: Boolean): ImageVector =
    when (action) {
        SwipeAction.TOGGLE_READ ->
            if (seen) Icons.Default.MarkEmailUnread else Icons.Default.MarkEmailRead
        SwipeAction.TOGGLE_STAR -> if (flagged) Icons.Default.StarBorder else Icons.Default.Star
        SwipeAction.ARCHIVE -> Icons.Default.Archive
        SwipeAction.TRASH -> Icons.Default.Delete
        SwipeAction.MOVE -> Icons.Default.DriveFileMove
        SwipeAction.DELETE -> Icons.Default.DeleteForever
        SwipeAction.NONE -> Icons.Default.Archive
    }

@Composable
private fun swipeColour(action: SwipeAction): Color = when (action) {
    SwipeAction.ARCHIVE -> MaterialTheme.colorScheme.primaryContainer
    SwipeAction.TRASH -> MaterialTheme.colorScheme.errorContainer
    SwipeAction.DELETE -> MaterialTheme.colorScheme.error
    SwipeAction.TOGGLE_READ -> MaterialTheme.colorScheme.secondaryContainer
    SwipeAction.TOGGLE_STAR -> MaterialTheme.colorScheme.tertiaryContainer
    SwipeAction.MOVE -> MaterialTheme.colorScheme.surfaceVariant
    SwipeAction.NONE -> Color.Transparent
}

@Composable
private fun swipeInk(action: SwipeAction): Color = when (action) {
    SwipeAction.ARCHIVE -> MaterialTheme.colorScheme.onPrimaryContainer
    SwipeAction.TRASH -> MaterialTheme.colorScheme.onErrorContainer
    SwipeAction.DELETE -> MaterialTheme.colorScheme.onError
    SwipeAction.TOGGLE_READ -> MaterialTheme.colorScheme.onSecondaryContainer
    SwipeAction.TOGGLE_STAR -> MaterialTheme.colorScheme.onTertiaryContainer
    SwipeAction.MOVE -> MaterialTheme.colorScheme.onSurfaceVariant
    SwipeAction.NONE -> Color.Transparent
}
