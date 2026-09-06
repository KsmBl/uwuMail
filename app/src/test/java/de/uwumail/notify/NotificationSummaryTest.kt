package de.uwumail.notify

import de.uwumail.data.db.AppDatabase
import de.uwumail.data.db.FolderEntity
import de.uwumail.data.db.MessageEntity
import de.uwumail.data.db.TestDatabase
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
 * Gathering an account's notifications under one line.
 *
 * The summary was posted only when two or more messages arrived in a single
 * sync. Push delivers mail one message at a time, so in normal use it was never
 * posted and nothing ever grouped — and when it was, it went out on the default
 * channel and could ring for mail a rule had deliberately silenced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationSummaryTest {

    private lateinit var db: AppDatabase
    private var accountId = 0L
    private var inbox = 0L

    @Before
    fun open() = runBlocking {
        db = TestDatabase.open()
        accountId = db.accountDao().insert(TestDatabase.account())
        inbox = db.folderDao().insert(
            FolderEntity(accountId = accountId, path = "INBOX", displayName = "Inbox", type = "INBOX")
        )
    }

    @After
    fun close() = db.close()

    private suspend fun arrived(uid: Long, notified: Boolean = true, seen: Boolean = false) =
        db.messageDao().insert(
            MessageEntity(
                accountId = accountId,
                folderId = inbox,
                uid = uid,
                subject = "Message $uid",
                receivedAt = uid,
                seen = seen,
                notified = notified
            )
        )

    @Test
    fun `one notification does not need a summary over it`() {
        assertFalse(Notifier.shouldPostSummary(1))
    }

    @Test
    fun `two notifications are worth grouping`() {
        assertTrue(Notifier.shouldPostSummary(2))
    }

    @Test
    fun `nothing standing means no summary`() {
        assertFalse(Notifier.shouldPostSummary(0))
    }

    @Test
    fun `mail arriving one at a time still adds up to a group`() = runBlocking {
        arrived(1)
        assertEquals(1, db.messageDao().standingNotifications(accountId))

        arrived(2)
        assertEquals(2, db.messageDao().standingNotifications(accountId))
        assertTrue(Notifier.shouldPostSummary(db.messageDao().standingNotifications(accountId)))
    }

    @Test
    fun `a message that has been read is no longer standing`() = runBlocking {
        val id = arrived(1)
        arrived(2)

        db.messageDao().setSeen(listOf(id), true)

        assertEquals(1, db.messageDao().standingNotifications(accountId))
    }

    @Test
    fun `mail that was never notified is not counted`() = runBlocking {
        arrived(1, notified = true)
        arrived(2, notified = false)

        assertEquals(1, db.messageDao().standingNotifications(accountId))
    }

    @Test
    fun `a message on its way out is not counted`() = runBlocking {
        val id = arrived(1)
        arrived(2)
        db.messageDao().setPendingRemoval(listOf(id), true)

        assertEquals(1, db.messageDao().standingNotifications(accountId))
    }

    @Test
    fun `another account's mail does not join this one's group`() = runBlocking {
        val other = db.accountDao().insert(TestDatabase.account("other@example.com"))
        val otherInbox = db.folderDao().insert(
            FolderEntity(accountId = other, path = "INBOX", displayName = "Inbox", type = "INBOX")
        )
        arrived(1)
        db.messageDao().insert(
            MessageEntity(
                accountId = other, folderId = otherInbox, uid = 1, subject = "Theirs", notified = true
            )
        )

        assertEquals(1, db.messageDao().standingNotifications(accountId))
    }
}
