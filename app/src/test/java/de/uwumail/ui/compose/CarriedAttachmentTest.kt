package de.uwumail.ui.compose

import de.uwumail.data.db.AttachmentEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What travels with a draft reopened or a message forwarded.
 *
 * Neither used to carry anything: reopening a draft and saving it again wrote
 * the draft back with its attachments removed, and a forward arrived without
 * the file that was usually the reason for forwarding it.
 */
class CarriedAttachmentTest {

    private fun part(
        name: String,
        inline: Boolean = false,
        contentId: String? = null
    ) = AttachmentEntity(
        id = name.hashCode().toLong(),
        messageId = 1,
        partId = "1",
        fileName = name,
        mimeType = "application/octet-stream",
        sizeBytes = 10,
        isInline = inline,
        contentId = contentId
    )

    private fun carried(vararg parts: AttachmentEntity) =
        ComposeViewModel.carriedParts(parts.toList()).map { it.fileName }

    @Test
    fun `a real attachment is carried`() {
        assertEquals(listOf("invoice.pdf"), carried(part("invoice.pdf")))
    }

    @Test
    fun `several attachments all come along`() {
        assertEquals(
            listOf("one.pdf", "two.docx", "three.png"),
            carried(part("one.pdf"), part("two.docx"), part("three.png"))
        )
    }

    @Test
    fun `an inline picture belongs to the body and is left behind`() {
        assertEquals(
            listOf("invoice.pdf"),
            carried(part("logo.png", inline = true, contentId = "<logo>"), part("invoice.pdf"))
        )
    }

    @Test
    fun `a part with no filename is not carried`() {
        assertEquals(listOf("invoice.pdf"), carried(part(""), part("invoice.pdf")))
    }

    @Test
    fun `a message with nothing attached carries nothing`() {
        assertEquals(emptyList<String>(), carried())
    }

    @Test
    fun `a message of only inline pictures carries nothing`() {
        assertEquals(
            emptyList<String>(),
            carried(part("a.png", inline = true), part("b.png", inline = true))
        )
    }
}
