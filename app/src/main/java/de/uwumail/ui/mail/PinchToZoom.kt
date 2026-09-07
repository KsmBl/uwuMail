package de.uwumail.ui.mail

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Reports pinches over the whole of whatever this is applied to.
 *
 * Written by hand rather than with `detectTransformGestures`, which claims a
 * gesture as soon as one finger has moved past the touch slop and would take
 * the scroll and the swipe between messages with it. Here nothing is consumed
 * until a second finger is down, so one finger still scrolls and swipes exactly
 * as before and two fingers mean zoom and nothing else.
 */
fun Modifier.pinchToZoom(enabled: Boolean = true, onZoom: (Float) -> Unit): Modifier =
    if (!enabled) this else pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f && zoom > 0f) {
                        onZoom(zoom)
                        event.changes.forEach { it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
