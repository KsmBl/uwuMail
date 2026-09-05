package de.uwumail.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlocklistParserTest {

    @Test
    fun `keeps a bare domain`() {
        assertEquals("spam.example", BlocklistParser.normalise("spam.example"))
    }

    @Test
    fun `lowercases and trims`() {
        assertEquals("spam.example", BlocklistParser.normalise("  SPAM.Example.  "))
    }

    @Test
    fun `reads the domain out of a hosts-file line`() {
        assertEquals("spam.example", BlocklistParser.normalise("0.0.0.0 spam.example"))
        assertEquals("spam.example", BlocklistParser.normalise("127.0.0.1\tspam.example"))
    }

    @Test
    fun `strips wildcard and leading dot prefixes`() {
        assertEquals("spam.example", BlocklistParser.normalise("*.spam.example"))
        assertEquals("spam.example", BlocklistParser.normalise(".spam.example"))
    }

    @Test
    fun `drops comments, blanks and list metadata`() {
        listOf("", "   ", "# Fake and Temp Mail Providers", "// note", "! adblock rule")
            .forEach { assertNull("should drop \"$it\"", BlocklistParser.normalise(it)) }
    }

    @Test
    fun `strips a trailing comment`() {
        assertEquals("spam.example", BlocklistParser.normalise("spam.example # known bad"))
    }

    @Test
    fun `rejects entries that would match far too much`() {
        // A bare TLD would flag every sender under it.
        assertNull(BlocklistParser.normalise("com"))
        assertNull(BlocklistParser.normalise("localhost"))
        // Raw addresses are not sender domains.
        assertNull(BlocklistParser.normalise("0.0.0.0"))
        assertNull(BlocklistParser.normalise("192.168.1.1"))
    }

    @Test
    fun `keeps a full address so a single sender can be blocked`() {
        assertEquals("someone@spam.example", BlocklistParser.normalise("someone@spam.example"))
    }

    @Test
    fun `parses a realistic list and de-duplicates`() {
        val list = """
            # Fake and Temp Mail Providers
            # https://github.com/7c/fakefilter

            0-mail.com
            0.0.0.0 mailinator.com
            *.guerrillamail.com
            MAILINATOR.COM
            localhost
            127.0.0.1
        """.trimIndent().lineSequence()

        assertEquals(
            listOf("0-mail.com", "mailinator.com", "guerrillamail.com"),
            BlocklistParser.parse(list)
        )
    }
}
