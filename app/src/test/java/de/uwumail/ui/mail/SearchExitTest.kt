package de.uwumail.ui.mail

import de.uwumail.core.FolderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where closing the search box leaves you.
 *
 * Widening a search to every folder takes the list off whatever it was showing.
 * Closing the box put it in All inboxes whatever it had been showing before,
 * so a search started half way down a folder cost you your place in it.
 */
class SearchExitTest {

    private val work = MailTarget.Folder(id = 12)

    @Test
    fun `a widened search goes back to the folder it started in`() {
        assertEquals(work, targetAfterSearch(current = MailTarget.Search, origin = work))
    }

    @Test
    fun `a widened search goes back to the unified view it started in`() {
        assertEquals(
            MailTarget.SENT,
            targetAfterSearch(current = MailTarget.Search, origin = MailTarget.SENT)
        )
    }

    @Test
    fun `a search that stayed in its folder leaves the list alone`() {
        assertNull(targetAfterSearch(current = work, origin = work))
    }

    @Test
    fun `a search that stayed in a unified view leaves the list alone`() {
        assertNull(targetAfterSearch(current = MailTarget.INBOXES, origin = MailTarget.INBOXES))
    }

    @Test
    fun `with nowhere remembered it falls back to the inbox`() {
        assertEquals(
            MailTarget.INBOXES,
            targetAfterSearch(current = MailTarget.Search, origin = null)
        )
    }

    @Test
    fun `the fallback is the inbox rather than nothing at all`() {
        val landing = targetAfterSearch(current = MailTarget.Search, origin = null)

        assertEquals(MailTarget.Unified(FolderType.INBOX), landing)
    }
}
