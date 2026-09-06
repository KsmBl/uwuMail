package de.uwumail.ui.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the reply bar is offered.
 *
 * Replying is the commonest thing anyone does with an open message, and it used
 * to be two taps into an overflow menu behind Archive and Trash.
 */
class ReplyBarTest {

    @Test
    fun `an open message offers a reply`() {
        assertTrue(showsReplyBar(hasMessage = true, gravity = false))
    }

    @Test
    fun `a message still loading offers nothing to answer`() {
        assertFalse(showsReplyBar(hasMessage = false, gravity = false))
    }

    @Test
    fun `letters on the floor are not a message to reply to`() {
        assertFalse(showsReplyBar(hasMessage = true, gravity = true))
    }

    @Test
    fun `nothing loaded and nothing standing still offers nothing`() {
        assertFalse(showsReplyBar(hasMessage = false, gravity = true))
    }
}
