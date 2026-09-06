package de.uwumail.mail

import de.uwumail.data.db.OutboxEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties
import javax.mail.Session
import javax.mail.internet.MimeMultipart

/**
 * What the recipient sees an attachment called.
 *
 * The outbox carries paths and nothing else, so the name on disk is the name
 * that goes out. A copy made unique with a timestamp in front of it arrives as
 * `1757203948123_invoice.pdf`, which is nobody's idea of a filename.
 */
class OutgoingAttachmentTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val sender = SmtpSender()

    private fun outbox(vararg paths: String) = OutboxEntity(
        accountId = 1,
        identityId = null,
        fromAddress = "me@example.com",
        fromName = "Me",
        to = "you@example.com",
        cc = "",
        bcc = "",
        replyTo = null,
        subject = "Here you go",
        bodyPlain = "See attached.",
        bodyHtml = null,
        attachmentPaths = paths.joinToString("\n"),
        createdAt = 0
    )

    private fun attachmentNames(item: OutboxEntity): List<String> {
        val message = sender.buildMessage(Session.getInstance(Properties()), item)
        val multipart = message.content as MimeMultipart
        return (0 until multipart.count)
            .mapNotNull { multipart.getBodyPart(it).fileName }
    }

    /** A picked file, kept unique by its own directory rather than by a prefix. */
    private fun picked(name: String): File {
        val directory = temporary.newFolder()
        return File(directory, name).apply { writeText("contents") }
    }

    @Test
    fun `an attachment goes out under the name it was picked with`() {
        val names = attachmentNames(outbox(picked("invoice.pdf").absolutePath))

        assertEquals(listOf("invoice.pdf"), names)
    }

    @Test
    fun `two files of the same name both go out under it`() {
        val names = attachmentNames(
            outbox(picked("photo.jpg").absolutePath, picked("photo.jpg").absolutePath)
        )

        assertEquals(listOf("photo.jpg", "photo.jpg"), names)
    }

    @Test
    fun `no attachment carries a timestamp it was never given`() {
        val names = attachmentNames(outbox(picked("report.pdf").absolutePath))

        assertTrue(names.none { it.contains(Regex("^\\d{10,}_")) })
    }

    @Test
    fun `a path that no longer exists is left out rather than sent empty`() {
        val names = attachmentNames(
            outbox(picked("real.pdf").absolutePath, "/nowhere/at/all/ghost.pdf")
        )

        assertEquals(listOf("real.pdf"), names)
    }
}
