package de.uwumail.ui

import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The back stack must never be left empty.
 *
 * A host with nothing on its stack draws nothing and answers nothing — an app
 * that can only be escaped by force-stopping it. That is the state a fast
 * second tap produced, and no test over pure functions could have seen it
 * coming, which is the reason this one runs a real NavHost.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NavigationGuardTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var controller: TestNavHostController
    private var secondScreen: NavBackStackEntry? = null

    private fun start() {
        compose.setContent {
            controller = TestNavHostController(LocalContext.current).apply {
                navigatorProvider.addNavigator(ComposeNavigator())
            }
            NavHost(controller, startDestination = FIRST) {
                composable(FIRST) { Text("first") }
                composable(SECOND) { entry ->
                    secondScreen = entry
                    Text("second")
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `going back from a screen returns to the one under it`() {
        start()
        compose.runOnUiThread { controller.navigate(SECOND) }
        compose.waitForIdle()
        assertEquals(SECOND, controller.currentDestination?.route)

        compose.runOnUiThread { controller.leave(secondScreen!!) }
        compose.waitForIdle()
        assertEquals(FIRST, controller.currentDestination?.route)
    }

    @Test
    fun `a second tap on a screen already left does nothing at all`() {
        start()
        compose.runOnUiThread { controller.navigate(SECOND) }
        compose.waitForIdle()
        val departing = secondScreen!!

        compose.runOnUiThread {
            // Both taps land before the departing screen has gone: the first
            // pops it, and the second used to pop the screen underneath.
            controller.leave(departing)
            controller.leave(departing)
        }
        compose.waitForIdle()

        assertNotNull("the host was left with nothing to draw", controller.currentDestination)
        assertEquals(FIRST, controller.currentDestination?.route)
    }

    @Test
    fun `a screen already left cannot open another one either`() {
        start()
        compose.runOnUiThread { controller.navigate(SECOND) }
        compose.waitForIdle()
        val departing = secondScreen!!

        compose.runOnUiThread {
            controller.leave(departing)
            // The same stale tap, on a menu entry rather than the back arrow.
            controller.go(departing, SECOND)
        }
        compose.waitForIdle()

        assertEquals(FIRST, controller.currentDestination?.route)
    }

    @Test
    fun `the screen in front is the one that may navigate`() {
        start()
        compose.runOnUiThread { controller.navigate(SECOND) }
        compose.waitForIdle()

        compose.runOnUiThread { controller.go(secondScreen!!, FIRST) }
        compose.waitForIdle()
        assertEquals(FIRST, controller.currentDestination?.route)
    }

    private companion object {
        const val FIRST = "first"
        const val SECOND = "second"
    }
}
