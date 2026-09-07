package de.uwumail.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gathering a list into conversations, run against a real database.
 *
 * A thread is represented by its newest message, carries the size of the whole
 * conversation, and is unread if any part of it is — a reply arriving on
 * something dealt with last week makes the conversation live again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversationQueryTest {

    private lateinit var db: AppDatabase
    private lateinit var messages: MessageDao
    private var inbox = 0L
    private var otherInbox = 0L
    private var archive = 0L

    @Before
    fun open() = runBlocking {
        db = TestDatabase.open()
        messages = db.messageDao()
        val one = db.accountDao().insert(TestDatabase.account("one@example.com"))
        val two = db.accountDao().insert(TestDatabase.account("two@example.com"))
        inbox = db.folderDao().insert(
            FolderEntity(accountId = one, path = "INBOX", displayName = "Inbox", type = "INBOX")
        )
        otherInbox = db.folderDao().insert(
            FolderEntity(accountId = two, path = "INBOX", displayName = "Inbox", type = "INBOX")
        )
        archive = db.folderDao().insert(
            FolderEntity(accountId = one, path = "Archive", displayName = "Archive", type = "ARCHIVE")
        )
    }

    @After
    fun close() = db.close()

    private suspend fun put(
        folderId: Long = 0,
        uid: Long,
        thread: String?,
        subject: String = "Message $uid",
        seen: Boolean = true,
        at: Long = uid
    ) = messages.insert(
        MessageEntity(
            accountId = 1,
            folderId = if (folderId == 0L) inbox else folderId,
            uid = uid,
            threadId = thread,
            subject = subject,
            receivedAt = at,
            seen = seen
        )
    )

    @Test
    fun `an exchange becomes one row`() = runBlocking {
        put(uid = 1, thread = "<root@x>", subject = "Lunch?")
        put(uid = 2, thread = "<root@x>", subject = "Re: Lunch?")
        put(uid = 3, thread = "<root@x>", subject = "Re: Lunch?")

        val rows = messages.observeFolderThreads(inbox, 50).first()

        assertEquals(1, rows.size)
        assertEquals(3, rows.first().threadCount)
    }

    @Test
    fun `the newest message stands for the conversation`() = runBlocking {
        put(uid = 1, thread = "<root@x>", subject = "Lunch?", at = 100)
        put(uid = 2, thread = "<root@x>", subject = "Re: Lunch? Yes", at = 200)

        assertEquals(
            "Re: Lunch? Yes",
            messages.observeFolderThreads(inbox, 50).first().first().subject
        )
    }

    @Test
    fun `separate conversations stay separate`() = runBlocking {
        put(uid = 1, thread = "<a@x>")
        put(uid = 2, thread = "<b@x>")

        assertEquals(2, messages.observeFolderThreads(inbox, 50).first().size)
    }

    @Test
    fun `a lone message is a conversation of one`() = runBlocking {
        put(uid = 1, thread = "<a@x>")

        assertEquals(1, messages.observeFolderThreads(inbox, 50).first().first().threadCount)
    }

    @Test
    fun `mail cached before threading keeps to itself`() = runBlocking {
        put(uid = 1, thread = null)
        put(uid = 2, thread = null)

        val rows = messages.observeFolderThreads(inbox, 50).first()

        assertEquals(2, rows.size)
        assertTrue(rows.all { it.threadCount == 1 })
    }

    @Test
    fun `a conversation with one unread reply reads as unread`() = runBlocking {
        put(uid = 1, thread = "<root@x>", seen = true, at = 100)
        put(uid = 2, thread = "<root@x>", seen = false, at = 200)

        assertFalse(messages.observeFolderThreads(inbox, 50).first().first().seen)
    }

    @Test
    fun `an unread message anywhere in a conversation keeps it unread`() = runBlocking {
        // The newest was read; something earlier in it never was.
        put(uid = 1, thread = "<root@x>", seen = false, at = 100)
        put(uid = 2, thread = "<root@x>", seen = true, at = 200)

        assertFalse(messages.observeFolderThreads(inbox, 50).first().first().seen)
    }

    @Test
    fun `a conversation read all the way through reads as read`() = runBlocking {
        put(uid = 1, thread = "<root@x>", seen = true, at = 100)
        put(uid = 2, thread = "<root@x>", seen = true, at = 200)

        assertTrue(messages.observeFolderThreads(inbox, 50).first().first().seen)
    }

    @Test
    fun `a conversation stays inside its own folder`() = runBlocking {
        put(uid = 1, thread = "<root@x>")
        put(folderId = archive, uid = 2, thread = "<root@x>")

        // The inbox half is one message, not two.
        assertEquals(1, messages.observeFolderThreads(inbox, 50).first().first().threadCount)
    }

    @Test
    fun `a unified view gathers across accounts`() = runBlocking {
        put(uid = 1, thread = "<root@x>")
        put(folderId = otherInbox, uid = 2, thread = "<root@x>")

        val rows = messages.observeUnifiedThreads("INBOX", 50).first()

        assertEquals(1, rows.size)
        assertEquals(2, rows.first().threadCount)
    }

    @Test
    fun `a removal in flight is not counted in a conversation`() = runBlocking {
        val id = put(uid = 1, thread = "<root@x>")
        put(uid = 2, thread = "<root@x>")
        messages.setPendingRemoval(listOf(id), true)

        assertEquals(1, messages.observeFolderThreads(inbox, 50).first().first().threadCount)
    }

    @Test
    fun `opening a conversation lists it oldest first`() = runBlocking {
        put(uid = 1, thread = "<root@x>", subject = "Lunch?", at = 100)
        put(uid = 2, thread = "<root@x>", subject = "Re: Lunch?", at = 200)

        val rows = messages.observeThread("<root@x>", 50).first()

        assertEquals(listOf("Lunch?", "Re: Lunch?"), rows.map { it.subject })
    }

    @Test
    fun `opening a conversation crosses the folders it spans`() = runBlocking {
        put(uid = 1, thread = "<root@x>", at = 100)
        put(folderId = archive, uid = 2, thread = "<root@x>", at = 200)

        assertEquals(2, messages.observeThread("<root@x>", 50).first().size)
    }

    @Test
    fun `the flat list still shows every message`() = runBlocking {
        put(uid = 1, thread = "<root@x>")
        put(uid = 2, thread = "<root@x>")

        val rows = messages.observeFolder(inbox, 50).first()

        assertEquals(2, rows.size)
        assertTrue(rows.all { it.threadCount == 1 })
    }
}
