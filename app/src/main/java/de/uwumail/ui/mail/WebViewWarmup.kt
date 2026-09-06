package de.uwumail.ui.mail

import android.content.Context
import android.webkit.WebView

/**
 * Starts the browser engine before a mail needs it.
 *
 * The first WebView in a process pays for loading and starting the whole
 * rendering engine, which is a few hundred milliseconds during which a message
 * has opened and its body is not there yet. Building one while the list is on
 * screen moves that cost to a moment nobody is waiting on. The view itself is
 * thrown away immediately; what survives is the engine, which is per process
 * and now already running.
 */
object WebViewWarmup {

    @Volatile
    private var started = false

    /** Safe to call as often as you like; only the first call does anything. */
    fun start(context: Context) {
        if (started) return
        started = true
        // A device with no WebView installed at all is a device where this was
        // never going to help; it must not be a device where the app crashes.
        runCatching { WebView(context.applicationContext).destroy() }
    }
}
