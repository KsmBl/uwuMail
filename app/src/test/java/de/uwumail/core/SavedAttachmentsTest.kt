package de.uwumail.core

import de.uwumail.core.SavedAttachments.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedAttachmentsTest {

    @Test
    fun `nothing found is not the same as nothing worked`() {
        assertEquals(Outcome.NOTHING_ATTACHED, SavedAttachments().outcome)
        assertEquals(Outcome.ALL_FAILED, SavedAttachments(missing = 3).outcome)
    }

    @Test
    fun `anything saved counts as saved`() {
        assertEquals(Outcome.SAVED, SavedAttachments(saved = 1).outcome)
        assertEquals(Outcome.SAVED, SavedAttachments(saved = 3, missing = 2).outcome)
    }

    @Test
    fun `a partial result has something more to say than its count`() {
        // The point of counting failures separately: three saved out of five
        // must not be reported as though five were asked for and three exist.
        assertTrue(SavedAttachments(saved = 3, missing = 2).partial)
        assertFalse(SavedAttachments(saved = 3).partial)
        assertFalse(SavedAttachments(missing = 2).partial)
    }
}
