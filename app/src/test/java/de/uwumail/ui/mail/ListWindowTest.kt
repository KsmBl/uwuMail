package de.uwumail.ui.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reaching the bottom of the list.
 *
 * The window used to be a fixed 300 rows and reaching the end went straight to
 * the server, so mail paged into the cache was never drawn: the list stopped at
 * 300 for ever while quietly downloading more of what it would not show.
 */
class ListWindowTest {

    @Test
    fun `a full window means there is more cached to draw`() {
        assertTrue(MailViewModel.canWiden(shown = 300, window = 300))
    }

    @Test
    fun `a list shorter than its window has run out of cached mail`() {
        assertFalse(MailViewModel.canWiden(shown = 274, window = 300))
    }

    @Test
    fun `an empty folder asks the server rather than widening`() {
        assertFalse(MailViewModel.canWiden(shown = 0, window = 300))
    }

    @Test
    fun `a window already widened keeps widening while it stays full`() {
        assertTrue(MailViewModel.canWiden(shown = 600, window = 600))
        assertTrue(MailViewModel.canWiden(shown = 900, window = 900))
    }

    @Test
    fun `one row short of the window is enough to stop widening`() {
        assertFalse(MailViewModel.canWiden(shown = 599, window = 600))
    }
}
