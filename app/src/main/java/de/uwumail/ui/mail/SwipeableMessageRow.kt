package de.uwumail.ui.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import de.uwumail.core.SwipeAction
import de.uwumail.data.settings.AppSettings
import de.uwumail.ui.common.rememberHaptics

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
    /** Fraction of the row a swipe must cross to count; see [AppSettings.swipeThresholdFraction]. */
    commitFraction: Float,
    onAction: (SwipeAction) -> Unit,
    content: @Composable () -> Unit
) {
    if (!enabled || (rightAction == SwipeAction.NONE && leftAction == SwipeAction.NONE)) {
        content()
        return
    }

    val currentOnAction by rememberUpdatedState(onAction)
    // Read through a holder so changing the setting takes effect on the rows
    // already composed, rather than only on the ones scrolled into view next.
    val currentFraction by rememberUpdatedState(commitFraction)
    val haptics = rememberHaptics()
    // The row's own width, which is the screen's. Read from the layout rather
    // than from the window so it is right whatever the row is sitting in.
    var rowWidth by remember { mutableIntStateOf(0) }
    // The state cannot be read from inside its own constructor, and the check
    // below needs how far the finger went, so it is handed back here once made.
    val settled = remember { arrayOfNulls<SwipeToDismissBoxState>(1) }
    // confirmValueChange is a question, and the box is entitled to ask it more
    // than once while a swipe settles. Acting on every ask is how one swipe
    // produced two removals, and two offers to undo them.
    val acted = remember { booleanArrayOf(false) }

    val state = rememberSwipeToDismissBoxState(
        // Whatever the setting says, and nothing less.
        positionalThreshold = { distance -> distance * currentFraction },
        confirmValueChange = { value ->
            val action = when (value) {
                SwipeToDismissBoxValue.StartToEnd -> rightAction
                SwipeToDismissBoxValue.EndToStart -> leftAction
                SwipeToDismissBoxValue.Settled -> SwipeAction.NONE
            }
            if (action == SwipeAction.NONE) return@rememberSwipeToDismissBoxState false
            if (acted[0]) return@rememberSwipeToDismissBoxState action.carriesRowAway &&
                !action.needsConfirmation

            // The positional threshold alone is not enough: a quick flick
            // commits on velocity however short it was, and something that
            // deletes mail should not be reachable by a flick. So how far the
            // finger actually went is checked as well, against the same
            // fraction of the row.
            val travelled = settled[0]
                ?.let { box -> runCatching { abs(box.requireOffset()) }.getOrNull() }
                ?: 0f
            if (rowWidth <= 0 || travelled < rowWidth * currentFraction) {
                return@rememberSwipeToDismissBoxState false
            }

            acted[0] = true
            currentOnAction(action)
            // Anything that needs an answer first has to spring back: the
            // question can still be answered with no.
            action.carriesRowAway && !action.needsConfirmation
        }
    )

    SideEffect { settled[0] = state }

    // A knock the moment the swipe passes the point where letting go would act.
    // The row is the same width either side of that point, so without it the
    // only way to know is to let go and find out.
    LaunchedEffect(state.targetValue) {
        if (state.targetValue != SwipeToDismissBoxValue.Settled) haptics.threshold()
    }

    // Ready for the next swipe once this one has come to rest.
    LaunchedEffect(state.targetValue, state.currentValue) {
        if (state.targetValue == SwipeToDismissBoxValue.Settled &&
            state.currentValue == SwipeToDismissBoxValue.Settled
        ) {
            acted[0] = false
        }
    }

    // A row on screen has not been swiped away, whatever it was doing last
    // time. LazyColumn keeps an item's state against its key and hands it back
    // when the row returns, so an undone removal used to come back still
    // wearing the colour of the action it escaped.
    LaunchedEffect(Unit) {
        if (state.currentValue != SwipeToDismissBoxValue.Settled) {
            runCatching { state.snapTo(SwipeToDismissBoxValue.Settled) }
        }
    }

    SwipeToDismissBox(
        modifier = Modifier.onSizeChanged { rowWidth = it.width },
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
            contentDescription = stringResource(action.label),
            tint = swipeInk(action),
            modifier = Modifier.size(24.dp)
        )
    }
}

/** The icon shows what is about to happen, so the toggles show their outcome. */
private fun swipeIcon(action: SwipeAction, seen: Boolean, flagged: Boolean): ImageVector =
    when (action) {
        SwipeAction.SELECT -> Icons.Default.CheckCircle
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
    SwipeAction.SELECT -> MaterialTheme.colorScheme.primaryContainer
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
    SwipeAction.SELECT -> MaterialTheme.colorScheme.onPrimaryContainer
    SwipeAction.ARCHIVE -> MaterialTheme.colorScheme.onPrimaryContainer
    SwipeAction.TRASH -> MaterialTheme.colorScheme.onErrorContainer
    SwipeAction.DELETE -> MaterialTheme.colorScheme.onError
    SwipeAction.TOGGLE_READ -> MaterialTheme.colorScheme.onSecondaryContainer
    SwipeAction.TOGGLE_STAR -> MaterialTheme.colorScheme.onTertiaryContainer
    SwipeAction.MOVE -> MaterialTheme.colorScheme.onSurfaceVariant
    SwipeAction.NONE -> Color.Transparent
}

/**
 * How far across the row a swipe must go before it counts.
 *
 * Half is deliberately a long way, and it is the default rather than the rule:
 * these actions move or delete mail, and the cost of one done by accident is
 * far higher than the cost of having to mean it — but how far half a screen is
 * depends on the screen and on the hand holding it, so the distance is set in
 * *Settings → Swipe actions* and arrives here as [SwipeableMessageRow]'s
 * `commitFraction`.
 */
