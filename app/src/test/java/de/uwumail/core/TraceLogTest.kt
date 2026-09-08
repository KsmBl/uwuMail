package de.uwumail.core

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The trace that has to survive being asked for.
 *
 * Mail that does not arrive leaves nothing behind: the screen shows what it
 * showed before. On a phone I cannot reach, this file is the whole evidence,
 * so the properties that matter are that it keeps going across app starts,
 * that it does not grow without limit, and — the part a naive cap gets wrong —
 * that reaching the limit does not empty it, since a log that clears itself is
 * empty exactly when somebody finally goes to look.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TraceLogTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getExternalFilesDir(null)!!
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun log(name: String, onScreen: Int = 400, maxBytes: Long = 256L * 1024) =
        TraceLog(name, "test", onScreen, maxBytes).apply {
            attach(ApplicationProvider.getApplicationContext())
        }

    @Test
    fun `a line written is a line kept`() {
        val log = log("one.txt")

        log.write("checking account 1")

        assertTrue(log.lines.value.single().endsWith("checking account 1"))
    }

    @Test
    fun `every line reaches the file, for the phone with a cable on it`() {
        val log = log("two.txt")

        log.write("first")
        log.write("second")

        val text = File(dir, "two.txt").readText()
        assertTrue(text.contains("first"))
        assertTrue(text.contains("second"))
    }

    /** The screen holds a window; the file is the record. */
    @Test
    fun `only the last lines are kept for the screen`() {
        val log = log("three.txt", onScreen = 3)

        repeat(10) { log.write("line $it") }

        assertEquals(3, log.lines.value.size)
        assertTrue(log.lines.value.last().endsWith("line 9"))
    }

    @Test
    fun `the file stops growing`() {
        val log = log("four.txt", maxBytes = 2_000)

        repeat(400) { log.write("a reasonably long line of trace, number $it") }

        assertTrue(File(dir, "four.txt").length() <= 4_000)
    }

    /**
     * The whole point of halving rather than clearing: what happened most
     * recently is what explains the fault, and it must still be there.
     */
    @Test
    fun `what happened last survives the file being trimmed`() {
        val log = log("five.txt", maxBytes = 2_000)

        repeat(400) { log.write("line $it") }

        val text = File(dir, "five.txt").readText()
        assertTrue("the newest line was trimmed away", text.contains("line 399"))
        assertFalse("the oldest line was kept", text.contains("line 0 "))
    }

    /** A check that ran while the app was closed is the one worth having. */
    @Test
    fun `a later run adds to what the last one left`() {
        log("six.txt").write("before the app was killed")

        log("six.txt").write("after it started again")

        val text = File(dir, "six.txt").readText()
        assertTrue(text.contains("before the app was killed"))
        assertTrue(text.contains("after it started again"))
    }

    /** The wizard's log is per-run, and says so by starting empty. */
    @Test
    fun `beginning a run clears what came before it`() {
        val log = log("seven.txt")
        log.write("an older run")

        log.begin("a new run")

        assertFalse(log.text().contains("an older run"))
        assertTrue(log.text().contains("a new run"))
    }

    @Test
    fun `a failure is recorded with where it came from`() {
        val log = log("eight.txt")

        log.failure("syncFolder", IllegalStateException("not connected"))

        assertTrue(log.text().contains("syncFolder"))
        assertTrue(log.text().contains("not connected"))
        assertTrue("no stack recorded", log.text().contains("    at "))
    }

    /** Which phone it was is the first question about a fault that is one phone's. */
    @Test
    fun `the device names itself`() {
        assertTrue(log("nine.txt").device().contains("Android"))
    }

    /** Nothing may throw out of a log: it is never the important part of a call. */
    @Test
    fun `writing before a file is attached is harmless`() {
        val log = TraceLog("ten.txt", "test")

        log.write("no file yet")

        assertTrue(log.text().contains("no file yet"))
        assertEquals("(not attached)", log.path())
    }
}
