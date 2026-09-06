package de.uwumail.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The search queries, run against a real database.
 *
 * A search that quietly returns the whole folder is indistinguishable from one
 * that works until you read the results, so these assert on what comes back
 * rather than on which method was called.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MessageSearchTest {

    private lateinit var db: AppDatabase
    private lateinit var messages: MessageDao

    private var inboxA = 0L
    private var inboxB = 0L
    private var archive = 0L

    @Before
    fun open() = runBlocking {
        db = TestDatabase.open()
        messages = db.messageDao()

        val one = db.accountDao().insert(TestDatabase.account("one@example.com"))
        val two = db.accountDao().insert(TestDatabase.account("two@example.com"))

        inboxA = db.folderDao().insert(
            FolderEntity(accountId = one, path = "INBOX", displayName = "Inbox", type = "INBOX")
        )
        inboxB = db.folderDao().insert(
            FolderEntity(accountId = two, path = "INBOX", displayName = "Inbox", type = "INBOX")
        )
        archive = db.folderDao().insert(
            FolderEntity(accountId = one, path = "Archive", displayName = "Archive", type = "ARCHIVE")
        )
    }

    @After
    fun close() = db.close()

    private suspend fun put(
        folderId: Long,
        uid: Long,
        subject: String = "",
        from: String? = null,
        to: String = "",
        preview: String = "",
        body: String? = null,
        accountId: Long = 1,
        pending: Boolean = false
    ) {
        messages.insert(
            MessageEntity(
                accountId = accountId,
                folderId = folderId,
                uid = uid,
                subject = subject,
                fromAddress = from,
                toList = to,
                preview = preview,
                bodyPlain = body,
                receivedAt = uid,
                pendingRemoval = pending
            )
        )
    }

    @Test
    fun `a query in a unified view filters it`() = runBlocking {
        put(inboxA, 1, subject = "Invoice for August")
        put(inboxB, 2, subject = "Holiday photos", accountId = 2)

        val hits = messages.searchUnified("INBOX", "invoice", 50).first()

        assertEquals(listOf("Invoice for August"), hits.map { it.subject })
    }

    @Test
    fun `a unified search reaches every account, not just the first`() = runBlocking {
        put(inboxA, 1, subject = "Invoice one")
        put(inboxB, 2, subject = "Invoice two", accountId = 2)

        val hits = messages.searchUnified("INBOX", "invoice", 50).first()

        assertEquals(2, hits.size)
    }

    @Test
    fun `a unified search stays inside its own folder role`() = runBlocking {
        put(inboxA, 1, subject = "Invoice inbox")
        put(archive, 2, subject = "Invoice archive")

        val hits = messages.searchUnified("INBOX", "invoice", 50).first()

        assertEquals(listOf("Invoice inbox"), hits.map { it.subject })
    }

    @Test
    fun `a unified search leaves out a hidden folder`() = runBlocking {
        put(inboxA, 1, subject = "Invoice visible")
        put(inboxB, 2, subject = "Invoice hidden", accountId = 2)
        db.folderDao().update(db.folderDao().get(inboxB)!!.copy(hidden = true))

        val hits = messages.searchUnified("INBOX", "invoice", 50).first()

        assertEquals(listOf("Invoice visible"), hits.map { it.subject })
    }

    @Test
    fun `a unified search leaves out a row whose removal is in flight`() = runBlocking {
        put(inboxA, 1, subject = "Invoice gone", pending = true)
        put(inboxA, 2, subject = "Invoice here")

        val hits = messages.searchUnified("INBOX", "invoice", 50).first()

        assertEquals(listOf("Invoice here"), hits.map { it.subject })
    }

    @Test
    fun `a folder search covers recipients`() = runBlocking {
        put(inboxA, 1, subject = "Nothing", to = "treasurer@club.org")

        val hits = messages.searchInFolder(inboxA, "treasurer", 50).first()

        assertEquals(1, hits.size)
    }

    @Test
    fun `a folder search covers the body`() = runBlocking {
        put(inboxA, 1, subject = "Nothing", body = "the meeting is on Tuesday")

        val hits = messages.searchInFolder(inboxA, "Tuesday", 50).first()

        assertEquals(1, hits.size)
    }

    @Test
    fun `the three searches agree on what a word matches`() = runBlocking {
        put(inboxA, 1, subject = "Nothing", to = "treasurer@club.org")

        assertEquals(1, messages.searchInFolder(inboxA, "treasurer", 50).first().size)
        assertEquals(1, messages.searchUnified("INBOX", "treasurer", 50).first().size)
        assertEquals(1, messages.searchEverywhere("treasurer", 50).first().size)
    }

    @Test
    fun `an empty unified view is not filtered by an unrelated query`() = runBlocking {
        put(inboxA, 1, subject = "Invoice for August")

        val hits = messages.searchUnified("INBOX", "nothing like this", 50).first()

        assertEquals(emptyList<String>(), hits.map { it.subject })
    }
}
