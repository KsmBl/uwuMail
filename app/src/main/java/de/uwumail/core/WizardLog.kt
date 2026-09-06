package de.uwumail.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A trace of one run of the rule wizard, kept where a phone on the end of a
 * cable can be asked for it.
 *
 * The wizard can still sit on its spinner on a device I cannot reproduce this
 * on, and a spinner says nothing about which step it stopped in. Every step
 * writes a line here, a watchdog writes one when a step outlasts its welcome,
 * and the whole thing lands in a file:
 *
 *     adb pull /sdcard/Android/data/de.uwumail/files/wizard-log.txt
 *
 * and, at the same time, in logcat:
 *
 *     adb logcat -s uwuMailWizard
 *
 * It is deliberately dumb: no coroutines, no buffering worth losing, and it
 * works before anything else has started. Off until [attach] is called, so the
 * unit tests that drive the suggester directly pay nothing for it.
 */
object WizardLog {

    private const val TAG = "uwuMailWizard"

    /** At most this many lines are kept for the screen; the file keeps all. */
    private const val ON_SCREEN = 400

    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** The trace so far, oldest first, for showing under the spinner. */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    @Volatile private var file: File? = null
    @Volatile private var startedAt = 0L

    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Points the log at a file. Safe to call more than once. */
    fun attach(context: Context) {
        if (file != null) return
        file = runCatching {
            File(context.getExternalFilesDir(null), "wizard-log.txt")
        }.getOrNull()
    }

    /** Begins a run: the file is truncated so it holds this attempt alone. */
    fun begin(what: String) {
        startedAt = System.currentTimeMillis()
        _lines.value = emptyList()
        runCatching { file?.writeText("") }
        write("=== $what ===")
        write("device ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
            "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
    }

    fun write(message: String) {
        val since = if (startedAt == 0L) 0 else System.currentTimeMillis() - startedAt
        val line = "%s +%6dms [%s] %s".format(
            clock.format(Date()), since, Thread.currentThread().name, message
        )
        Log.i(TAG, line)
        _lines.value = (_lines.value + line).takeLast(ON_SCREEN)
        runCatching { file?.appendText(line + "\n") }
    }

    /** Records a failure with its stack, which is the part worth having. */
    fun failure(where: String, error: Throwable) {
        write("FAILED in $where: ${error::class.java.name}: ${error.message}")
        error.stackTrace.take(12).forEach { write("    at $it") }
    }

    /**
     * Where a stuck thread actually is. A watchdog line saying a step is slow
     * names the step; this names the line of code, which is the difference
     * between knowing the analysis is stuck and knowing what it is stuck on.
     */
    fun stackOf(thread: Thread?) {
        val stack = thread?.stackTrace ?: return
        write("stack of ${thread.name}:")
        stack.take(20).forEach { write("    at $it") }
    }

    /** The whole trace, for sharing off the device. */
    fun text(): String = _lines.value.joinToString("\n")

    /** Where the file went, for saying so on screen. */
    fun path(): String = file?.absolutePath ?: "(not attached)"
}
