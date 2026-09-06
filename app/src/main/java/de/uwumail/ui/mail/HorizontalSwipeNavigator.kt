package de.uwumail.ui.mail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Moves between messages with a horizontal drag, and shows the content moving
 * with the finger so the gesture is answered rather than merely obeyed.
 *
 * The drag is watched in the [PointerEventPass.Initial] pass, which is the only
 * way to get it: the body is a WebView, and an Android view inside Compose
 * takes the whole touch stream once it has it. Nothing is consumed until the
 * drag is clearly sideways and there is somewhere to go, so taps, links,
 * text selection and vertical scrolling are all left entirely alone.
 */
@Composable
fun HorizontalSwipeNavigator(
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val goPrevious by rememberUpdatedState(onPrevious)
    val goNext by rememberUpdatedState(onNext)
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var width by remember { mutableIntStateOf(1) }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(canGoPrevious, canGoNext) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial
                    )
                    var travelledX = 0f
                    var travelledY = 0f
                    var claimed = false

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break

                        val movement = change.positionChange()
                        travelledX += movement.x
                        travelledY += movement.y

                        if (!claimed) {
                            // Anything that looks like a scroll belongs to the
                            // body, and once it is theirs it stays theirs.
                            if (abs(travelledY) > touchSlop) break
                            if (abs(travelledX) <= touchSlop * 2) continue
                            if (abs(travelledX) <= abs(travelledY) * 1.5f) continue
                            val towardsNext = travelledX < 0
                            if (towardsNext && !canGoNext) break
                            if (!towardsNext && !canGoPrevious) break
                            claimed = true
                        }

                        change.consume()
                        scope.launch {
                            val next = offset.value + movement.x
                            // No rubber band past the ends; there is nothing there.
                            offset.snapTo(
                                next.coerceIn(
                                    if (canGoNext) -width.toFloat() else 0f,
                                    if (canGoPrevious) width.toFloat() else 0f
                                )
                            )
                        }
                    }

                    if (!claimed) return@awaitEachGesture
                    val settled = offset.value
                    val far = abs(settled) > width * COMMIT_FRACTION
                    scope.launch {
                        offset.animateTo(0f, tween(SPRING_BACK_MILLIS))
                        if (far) if (settled < 0) goNext() else goPrevious()
                    }
                }
            }
    ) {
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }) { content() }
    }
}

/** How far across before letting go counts as asking for the next message. */
private const val COMMIT_FRACTION = 0.3f
private const val SPRING_BACK_MILLIS = 180
