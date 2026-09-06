package de.uwumail.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a blanket EXPUNGE may be used.
 *
 * UID EXPUNGE removes exactly what was named. A plain EXPUNGE removes every
 * `\Deleted` message in the mailbox, so falling back to it on a server without
 * UIDPLUS is how mail another client had flagged, and not finished with, stops
 * existing. It is only equivalent when ours are the only ones flagged.
 */
class ExpungeSafetyTest {

    private fun safe(ours: Set<Long>, flagged: Set<Long>) =
        ImapClient.blanketExpungeIsSafe(ours, flagged)

    @Test
    fun `expunging is safe when ours are the only ones flagged`() {
        assertTrue(safe(ours = setOf(1L, 2L), flagged = setOf(1L, 2L)))
    }

    @Test
    fun `expunging is safe when nothing at all is flagged`() {
        assertTrue(safe(ours = setOf(1L, 2L), flagged = emptySet()))
    }

    @Test
    fun `a message someone else flagged makes it unsafe`() {
        assertFalse(safe(ours = setOf(1L), flagged = setOf(1L, 99L)))
    }

    @Test
    fun `a stranger's message alone makes it unsafe`() {
        assertFalse(safe(ours = setOf(1L), flagged = setOf(99L)))
    }

    @Test
    fun `ours having already gone does not make it unsafe`() {
        assertTrue(safe(ours = setOf(1L, 2L), flagged = setOf(2L)))
    }

    @Test
    fun `one stranger among many of ours is enough to refuse`() {
        assertFalse(safe(ours = (1L..50L).toSet(), flagged = (1L..50L).toSet() + 51L))
    }
}
