package de.uwumail.ui.common

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * The small knocks that answer a gesture.
 *
 * These go through the view rather than Compose's own haptics because the
 * platform has a knock for each kind of moment and Compose only offers two.
 * Nothing here overrides the phone's haptics setting: a user who has turned
 * touch feedback off gets silence, which is what they asked for.
 */
class Haptics(private val view: View) {

    /** A swipe has gone far enough that letting go would do something. */
    fun threshold() = knock(HapticFeedbackConstants.CLOCK_TICK)

    /** A press held long enough to have meant it. */
    fun longPress() = knock(HapticFeedbackConstants.LONG_PRESS)

    /** Something has just happened. */
    fun confirm() = knock(HapticFeedbackConstants.CONFIRM)

    private fun knock(constant: Int) {
        view.performHapticFeedback(constant)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
