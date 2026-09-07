package de.uwumail.mail

import de.uwumail.data.db.MessageEntity
import de.uwumail.data.db.TestDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reply built from a line typed into the shade.
 *
 * There is no screen behind it and nothing to correct afterwards, so every part
 * has to be right first time: who it goes to, which of your own addresses it
 * comes from, and the headers that keep it in the same thread.
 */
class ReplyDraftTest {

    private val account = TestDatabase.account("me@example.com").copy(
        id = 1, displayName = "Me"
    )

    private fun message(
        from: String? = "them@elsewhere.org",
        replyTo: String? = null,
        to: String = "me@example.com",
        subject: String = "Question",
        messageId: String? = "<abc@elsewhere.org>"
    ) = MessageEntity(
        id = 42, accountId = 1, folderId = 1, uid = 1,
        messageIdHeader = messageId, subject = subject,
        fromAddress = from, replyTo = replyTo, toList = to
    )

    private fun draft(
        message: MessageEntity = message(),
        own: List<String> = listOf("me@example.com"),
        headers: Map<String, List<String>> = emptyMap(),
        body: String = "On my way"
    ) = ReplyDraft.build(account, message, own, headers, body, now = 1000L)

    @Test
    fun `the reply goes to whoever sent it`() {
        assertEquals("them@elsewhere.org", draft().to)
    }

    @Test
    fun `a reply-to header wins over the sender`() {
        val reply = draft(message(replyTo = "list@elsewhere.org"))

        assertEquals("list@elsewhere.org", reply.to)
    }

    @Test
    fun `the typed line is the whole body`() {
        assertEquals("On my way", draft(body = "On my way").bodyPlain)
    }

    @Test
    fun `the subject gains one Re`() {
        assertEquals("Re: Question", draft().subject)
    }

    @Test
    fun `a subject that already answers does not gain another Re`() {
        assertEquals("Re: Question", draft(message(subject = "Re: Question")).subject)
        assertEquals("RE: Question", draft(message(subject = "RE: Question")).subject)
    }

    @Test
    fun `a message with no subject still replies`() {
        assertEquals("Re:", draft(message(subject = "")).subject)
    }

    @Test
    fun `it comes from the alias the mail was addressed to`() {
        val reply = draft(
            message = message(to = "alias@example.com, someone@else.org"),
            own = listOf("me@example.com", "alias@example.com")
        )

        assertEquals("alias@example.com", reply.fromAddress)
    }

    @Test
    fun `it falls back to the account's own address`() {
        val reply = draft(message(to = "list@elsewhere.org"), own = listOf("me@example.com"))

        assertEquals("me@example.com", reply.fromAddress)
    }

    @Test
    fun `it stays in the thread it answers`() {
        val reply = draft(headers = mapOf("references" to listOf("<first@x> <second@x>")))

        assertEquals("<abc@elsewhere.org>", reply.inReplyTo)
        assertEquals("<first@x> <second@x> <abc@elsewhere.org>", reply.references)
    }

    @Test
    fun `a thread with no references so far starts one`() {
        assertEquals("<abc@elsewhere.org>", draft().references)
    }

    @Test
    fun `a message with no id of its own carries no references`() {
        assertNull(draft(message(messageId = null)).references)
    }

    @Test
    fun `the original is flagged answered once the reply goes`() {
        assertEquals(42L, draft().answeringMessageId)
    }

    @Test
    fun `a shade reply grew from no draft`() {
        assertNull(draft().draftMessageId)
    }

    @Test
    fun `nothing is copied to anyone else`() {
        val reply = draft()

        assertTrue(reply.cc.isEmpty())
        assertTrue(reply.bcc.isEmpty())
    }
}
