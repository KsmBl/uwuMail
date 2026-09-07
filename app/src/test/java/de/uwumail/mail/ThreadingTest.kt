package de.uwumail.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Working out which conversation a message belongs to.
 *
 * Every message in a thread agrees on the id its `References` chain starts
 * with, which is what makes gathering by it possible without walking a tree.
 */
class ThreadingTest {

    private fun thread(
        messageId: String? = null,
        inReplyTo: String? = null,
        references: String? = null,
        fallback: String = "uid:1:1"
    ) = Threading.threadIdOf(messageId, inReplyTo, references, fallback)

    @Test
    fun `the root of the references chain is the thread`() {
        assertEquals(
            "<root@example.com>",
            thread(
                messageId = "<third@example.com>",
                inReplyTo = "<second@example.com>",
                references = "<root@example.com> <second@example.com>"
            )
        )
    }

    @Test
    fun `with no references the message being answered is the thread`() {
        assertEquals(
            "<first@example.com>",
            thread(messageId = "<second@example.com>", inReplyTo = "<first@example.com>")
        )
    }

    @Test
    fun `a message starting a thread is the root of it`() {
        assertEquals("<first@example.com>", thread(messageId = "<first@example.com>"))
    }

    @Test
    fun `a whole exchange agrees on one thread`() {
        val opener = thread(messageId = "<a@x>")
        val reply = thread(messageId = "<b@x>", inReplyTo = "<a@x>", references = "<a@x>")
        val third = thread(messageId = "<c@x>", inReplyTo = "<b@x>", references = "<a@x> <b@x>")

        assertEquals(opener, reply)
        assertEquals(opener, third)
    }

    @Test
    fun `a message with nothing to go on keeps to itself`() {
        assertEquals("uid:7:99", thread(fallback = "uid:7:99"))
    }

    @Test
    fun `two unidentifiable messages are not lumped together`() {
        assertNotEquals(thread(fallback = "uid:1:1"), thread(fallback = "uid:1:2"))
    }

    @Test
    fun `references separated by commas or newlines still read`() {
        assertEquals("<root@x>", thread(references = "<root@x>,\n <second@x>"))
        assertEquals("<root@x>", thread(references = "\n\t<root@x>\n\t<second@x>"))
    }

    @Test
    fun `a header holding prose rather than an id is ignored`() {
        // Servers put all sorts here; a fragment of text is not a thread root.
        assertEquals(
            "<mine@x>",
            thread(messageId = "<mine@x>", references = "your message of yesterday")
        )
    }

    @Test
    fun `an unterminated bracket is not an id`() {
        assertEquals("<mine@x>", thread(messageId = "<mine@x>", references = "<broken"))
    }

    @Test
    fun `empty brackets are not an id`() {
        assertEquals("<mine@x>", thread(messageId = "<mine@x>", inReplyTo = "<>"))
    }

    @Test
    fun `blank headers fall through to the next thing`() {
        assertEquals(
            "<mine@x>",
            thread(messageId = "<mine@x>", inReplyTo = "   ", references = "")
        )
    }

    @Test
    fun `the header map reads the same way`() {
        assertEquals(
            "<root@x>",
            Threading.threadIdOf(
                messageId = "<second@x>",
                headers = mapOf(
                    "references" to listOf("<root@x> <first@x>"),
                    "in-reply-to" to listOf("<first@x>")
                ),
                fallback = "uid:1:1"
            )
        )
    }

    @Test
    fun `subjects are deliberately not used to gather threads`() {
        // "Re: lunch?" from two people in a week is two conversations, and
        // merging them puts one person's mail inside another's.
        val one = thread(messageId = "<a@x>")
        val other = thread(messageId = "<b@y>")

        assertNotEquals(one, other)
    }
}
