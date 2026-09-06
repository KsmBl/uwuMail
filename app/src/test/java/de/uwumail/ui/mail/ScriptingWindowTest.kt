package de.uwumail.ui.mail

import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The moment scripting is switched on to measure a page.
 *
 * A mail body is untrusted and renders with scripting off. Measuring where its
 * characters sit needs `evaluateJavascript`, which does nothing while scripting
 * is off, so it is turned on for the length of one call — and the network is
 * shut for that window, so nothing reachable from a handler can call out while
 * it is open. Both settings go back however the call ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScriptingWindowTest {

    private fun webView() = WebView(ApplicationProvider.getApplicationContext())

    @Test
    fun `scripting is on inside the window`() = runBlocking {
        val web = webView()
        web.settings.javaScriptEnabled = false

        val inside = web.withScripting(restoreTo = false) { settings.javaScriptEnabled }

        assertTrue(inside)
    }

    @Test
    fun `the network is shut inside the window`() = runBlocking {
        val web = webView()
        web.settings.blockNetworkLoads = false

        val blocked = web.withScripting(restoreTo = false) { settings.blockNetworkLoads }

        assertTrue(blocked)
    }

    @Test
    fun `scripting goes back off afterwards`() = runBlocking {
        val web = webView()
        web.settings.javaScriptEnabled = false

        web.withScripting(restoreTo = false) { }

        assertFalse(web.settings.javaScriptEnabled)
    }

    @Test
    fun `a body allowed scripts keeps them afterwards`() = runBlocking {
        val web = webView()

        web.withScripting(restoreTo = true) { }

        assertTrue(web.settings.javaScriptEnabled)
    }

    @Test
    fun `the network setting is restored to what it was`() = runBlocking {
        val web = webView()
        web.settings.blockNetworkLoads = false

        web.withScripting(restoreTo = false) { }

        assertFalse(web.settings.blockNetworkLoads)
    }

    @Test
    fun `images already allowed stay allowed afterwards`() = runBlocking {
        val web = webView()
        web.settings.blockNetworkLoads = true

        web.withScripting(restoreTo = false) { }

        assertTrue(web.settings.blockNetworkLoads)
    }

    @Test
    fun `a measurement that throws still shuts the door`() = runBlocking {
        val web = webView()
        web.settings.javaScriptEnabled = false
        web.settings.blockNetworkLoads = false

        runCatching { web.withScripting<Unit>(restoreTo = false) { error("measurement failed") } }

        assertFalse(web.settings.javaScriptEnabled)
        assertFalse(web.settings.blockNetworkLoads)
    }

    @Test
    fun `the window hands back what was measured`() = runBlocking {
        assertEquals("measured", webView().withScripting(restoreTo = false) { "measured" })
    }
}
