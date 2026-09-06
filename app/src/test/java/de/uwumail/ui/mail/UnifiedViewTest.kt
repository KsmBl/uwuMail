package de.uwumail.ui.mail

import de.uwumail.R
import de.uwumail.core.FolderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three views across every account.
 *
 * The Sent view was called "All outboxes", which on a phone means the opposite
 * of what it showed: everywhere else an outbox holds mail that has not gone
 * yet. uwuMail now has one of those too, which made the old name worse than
 * merely odd.
 */
class UnifiedViewTest {

    @Test
    fun `the sent view reads every account's Sent folder`() {
        assertEquals(MailTarget.Unified(FolderType.SENT), MailTarget.SENT)
    }

    @Test
    fun `the sent view is not named after the outbox`() {
        val label = MailTarget.UNIFIED.first { it.first == MailTarget.SENT }.second

        assertEquals(R.string.all_sent, label)
    }

    @Test
    fun `there are three unified views and each has its own label`() {
        assertEquals(3, MailTarget.UNIFIED.size)
        assertEquals(3, MailTarget.UNIFIED.map { it.second }.distinct().size)
    }

    @Test
    fun `the unified views cover inbox, sent and trash`() {
        val types = MailTarget.UNIFIED.map { (it.first as MailTarget.Unified).type }

        assertEquals(listOf(FolderType.INBOX, FolderType.SENT, FolderType.TRASH), types)
    }

    @Test
    fun `the inbox comes first`() {
        assertTrue(MailTarget.UNIFIED.first().first == MailTarget.INBOXES)
    }
}
