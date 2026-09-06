package de.uwumail.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedAttachmentsTest {

    private val folder = "Downloads/uwuMail"

    private fun summary(
        saved: Int = 0,
        missing: Int = 0,
        selection: Int = 1
    ) = SavedAttachments(saved, missing).summary(selection, folder)

    @Test
    fun `counts one file in the singular`() {
        assertEquals("1 file saved to Downloads/uwuMail", summary(saved = 1))
    }

    @Test
    fun `counts several in the plural`() {
        assertEquals("7 files saved to Downloads/uwuMail", summary(saved = 7))
    }

    @Test
    fun `says so when there was nothing attached`() {
        assertEquals("Nothing attached to that message", summary(selection = 1))
        assertEquals("Nothing attached to those messages", summary(selection = 4))
    }

    @Test
    fun `a partial result reports what failed rather than hiding it`() {
        assertEquals(
            "3 files saved to Downloads/uwuMail · 2 could not be fetched",
            summary(saved = 3, missing = 2)
        )
    }

    @Test
    fun `a total failure is not reported as success`() {
        assertEquals("Could not fetch that attachment", summary(missing = 1))
        assertEquals("Could not fetch those 3 attachments", summary(missing = 3))
    }
}
