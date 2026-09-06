package de.uwumail.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The paperclip drawn from the envelope alone.
 *
 * Reading the parts means fetching the message, so the list has only the
 * content type to go on. Counting `multipart/related` put a paperclip on every
 * HTML newsletter, whose "attachments" are the pictures its own body draws
 * with and which nobody can open or save.
 */
class AttachmentHintTest {

    @Test
    fun `a body with something beside it is attached`() {
        assertTrue(ImapClient.looksLikeAttachments("multipart/mixed; boundary=abc"))
    }

    @Test
    fun `a newsletter drawing its own pictures is not`() {
        assertFalse(ImapClient.looksLikeAttachments("multipart/related; boundary=abc"))
    }

    @Test
    fun `a plain text mail is not`() {
        assertFalse(ImapClient.looksLikeAttachments("text/plain; charset=utf-8"))
    }

    @Test
    fun `an html and text alternative is not`() {
        assertFalse(ImapClient.looksLikeAttachments("multipart/alternative; boundary=abc"))
    }

    @Test
    fun `the type is read whatever case the server sent it in`() {
        assertTrue(ImapClient.looksLikeAttachments("MULTIPART/MIXED; BOUNDARY=abc"))
    }

    @Test
    fun `leading whitespace does not hide the type`() {
        assertTrue(ImapClient.looksLikeAttachments("  multipart/mixed; boundary=abc"))
    }

    @Test
    fun `an envelope with no content type is not attached`() {
        assertFalse(ImapClient.looksLikeAttachments(""))
    }
}
