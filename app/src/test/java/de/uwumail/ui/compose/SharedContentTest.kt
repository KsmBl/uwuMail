package de.uwumail.ui.compose

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reading a share from another app.
 *
 * uwuMail was not in the share sheet at all, so mailing a photo meant saving it
 * somewhere first and picking it up again from the composer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SharedContentTest {

    private fun send(build: Intent.() -> Unit) =
        SharedContent.of(Intent(Intent.ACTION_SEND).apply(build))

    @Test
    fun `shared text becomes the body`() {
        val shared = send { putExtra(Intent.EXTRA_TEXT, "have a look at this") }

        assertEquals("have a look at this", shared.text)
    }

    @Test
    fun `a shared subject is kept`() {
        val shared = send { putExtra(Intent.EXTRA_SUBJECT, "Holiday photo") }

        assertEquals("Holiday photo", shared.subject)
    }

    @Test
    fun `a shared file becomes an attachment`() {
        val shared = send { putExtra(Intent.EXTRA_STREAM, Uri.parse("content://photos/1")) }

        assertEquals(listOf(Uri.parse("content://photos/1")), shared.attachments)
    }

    @Test
    fun `several shared files all become attachments`() {
        val uris = arrayListOf(Uri.parse("content://photos/1"), Uri.parse("content://photos/2"))
        val shared = SharedContent.of(
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(
                Intent.EXTRA_STREAM, uris
            )
        )

        assertEquals(2, shared.attachments.size)
    }

    @Test
    fun `addresses sent as an array are joined`() {
        val shared = send {
            putExtra(Intent.EXTRA_EMAIL, arrayOf("one@example.com", "two@example.com"))
        }

        assertEquals("one@example.com, two@example.com", shared.to)
    }

    @Test
    fun `an address sent as a plain string is accepted too`() {
        val shared = send { putExtra(Intent.EXTRA_EMAIL, "one@example.com") }

        assertEquals("one@example.com", shared.to)
    }

    @Test
    fun `copies and blind copies come through`() {
        val shared = send {
            putExtra(Intent.EXTRA_CC, arrayOf("cc@example.com"))
            putExtra(Intent.EXTRA_BCC, arrayOf("bcc@example.com"))
        }

        assertEquals("cc@example.com", shared.cc)
        assertEquals("bcc@example.com", shared.bcc)
    }

    @Test
    fun `a share carrying nothing at all is empty`() {
        assertTrue(send { }.isEmpty)
    }

    @Test
    fun `a share carrying anything is not empty`() {
        assertFalse(send { putExtra(Intent.EXTRA_TEXT, "hello") }.isEmpty)
    }

    @Test
    fun `the bus hands a share over exactly once`() {
        val bus = SharedContentBus()
        bus.offer(SharedContent(subject = "Once"))

        assertEquals("Once", bus.take()?.subject)
        assertNull(bus.take())
    }
}
