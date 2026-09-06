package de.uwumail.ui

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController

/**
 * Whether this screen is still the one in front.
 *
 * A screen stays composed, and keeps receiving taps, while it animates away.
 * Without this a quick second tap in the same spot is delivered to the screen
 * the user has already left, and acts a second time — the back arrow of a
 * departing screen pops the screen underneath it as well. Popping the last one
 * leaves the host with nothing to draw: a blank window that answers nothing.
 */
internal fun NavBackStackEntry.isInFront() =
    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

/** Goes back, but only on behalf of the screen actually in front. */
internal fun NavHostController.leave(from: NavBackStackEntry) {
    if (!from.isInFront()) return
    // Nothing underneath means this is the first screen, and it must not be
    // left with the host empty.
    if (previousBackStackEntry == null || !popBackStack()) {
        navigate(Routes.MAIL) { launchSingleTop = true }
    }
}

/** Opens [route], but only on behalf of the screen actually in front. */
internal fun NavHostController.go(from: NavBackStackEntry, route: String) {
    if (from.isInFront()) navigate(route)
}

