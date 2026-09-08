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
 * A trace kept where a phone on the other end of a conversation can be asked
 * for it.
 *
 * Some faults only happen on a device I cannot reach, and the symptom is
 * usually that nothing happens: a spinner that never stops, a refresh that
 * puts back what was already there. Neither says which step it got to. Every
 * step writes a line here, and the whole thing lands in a file that can be
 * shared out of the app, and in logcat for anyone with a cable.
 *
 * Deliberately dumb: no coroutines, no buffering worth losing, and it works
 * before anything else has started.
 */
open class TraceLog(
    private val fileName: String,
    private val tag: String,
    /** How many lines are kept for the screen; the file keeps more. */
    private val onScreen: Int = 400,
    /** How large the file may grow before the oldest half of it is dropped. */
    private val maxFileBytes: Long = 256L * 1024
) {

    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** The trace so far, oldest first. */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    @Volatile private var file: File? = null
    @Volatile private var startedAt = 0L

    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Points the log at a file. Safe to call more than once. */
    fun attach(context: Context) {
        if (file != null) return
        file = runCatching { File(context.getExternalFilesDir(null), fileName) }.getOrNull()
    }

    /** Begins a run: the file is truncated so it holds this attempt alone. */
    fun begin(what: String) {
        startedAt = System.currentTimeMillis()
        _lines.value = emptyList()
        runCatching { file?.writeText("") }
        write("=== $what ===")
        write(device())
    }

    /** Names the device, which is the first question about a fault that is one device's. */
    fun device(): String =
        "device ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
            "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"

    fun write(message: String) {
        val since = if (startedAt == 0L) 0 else System.currentTimeMillis() - startedAt
        val line = "%s +%6dms [%s] %s".format(
            clock.format(Date()), since, Thread.currentThread().name, message
        )
        Log.i(tag, line)
        _lines.value = (_lines.value + line).takeLast(onScreen)
        runCatching {
            file?.let { target ->
                target.appendText(line + "\n")
                if (target.length() > maxFileBytes) halve(target)
            }
        }
    }

    /** Records a failure with its stack, which is the part worth having. */
    fun failure(where: String, error: Throwable) {
        write("FAILED in $where: ${error::class.java.name}: ${error.message}")
        error.stackTrace.take(12).forEach { write("    at $it") }
    }

    /** The whole trace, for sharing off the device. */
    fun text(): String = _lines.value.joinToString("\n")

    /** Where the file went, for saying so on screen. */
    fun path(): String = file?.absolutePath ?: "(not attached)"

    /**
     * Drops the oldest half rather than the whole thing.
     *
     * A log that empties itself on reaching its limit is empty exactly when it
     * is asked for; keeping the recent half means the last thing that happened
     * is always still there.
     */
    private fun halve(target: File) {
        val kept = target.readLines().let { it.drop(it.size / 2) }
        target.writeText(kept.joinToString("\n", postfix = "\n"))
    }
}

/**
 * A trace of one run of the rule wizard.
 *
 *     adb pull /sdcard/Android/data/de.uwumail/files/wizard-log.txt
 *     adb logcat -s uwuMailWizard
 */
object WizardLog : TraceLog("wizard-log.txt", "uwuMailWizard") {

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
}

/**
 * A running account of what fetching mail did, and did not do.
 *
 *     adb pull /sdcard/Android/data/de.uwumail/files/sync-log.txt
 *     adb logcat -s uwuMailSync
 *
 * Unlike the wizard's, this one is not per-run: the question it exists to
 * answer is usually about mail that did not arrive while nobody was looking,
 * so it keeps going across background checks and starts of the app, and drops
 * its oldest half when it gets too large.
 */
object SyncLog : TraceLog("sync-log.txt", "uwuMailSync", onScreen = 600)
