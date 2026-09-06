package de.uwumail.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentIdTest {

    @Test
    fun `the header's angle brackets are not part of the name`() {
        assertTrue(ContentId.matches("logo@example.com", "<logo@example.com>"))
    }

    @Test
    fun `case does not matter`() {
        assertTrue(ContentId.matches("LOGO@Example.COM", "<logo@example.com>"))
    }

    @Test
    fun `a reference that arrived percent-encoded still matches`() {
        assertTrue(ContentId.matches("logo%40example.com", "<logo@example.com>"))
    }

    @Test
    fun `surrounding whitespace is ignored on both sides`() {
        assertTrue(ContentId.matches(" logo@example.com ", " <logo@example.com> "))
    }

    @Test
    fun `different parts do not match each other`() {
        assertFalse(ContentId.matches("logo@example.com", "<banner@example.com>"))
    }

    @Test
    fun `an empty or missing id never matches`() {
        assertFalse(ContentId.matches("", "<logo@example.com>"))
        assertFalse(ContentId.matches(null, "<logo@example.com>"))
        assertFalse(ContentId.matches("logo@example.com", null))
        // Two parts that both lack an id are not the same part.
        assertFalse(ContentId.matches("", ""))
    }
}
