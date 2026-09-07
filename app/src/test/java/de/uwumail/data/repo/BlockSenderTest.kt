package de.uwumail.data.repo

import de.uwumail.data.db.AppDatabase
import de.uwumail.data.db.FolderEntity
import de.uwumail.data.db.MessageEntity
import de.uwumail.data.db.TestDatabase
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
 * Blocking the sender of the message being read.
 *
 * The blocklist had a repository, a screen of its own and rows drawn in red,
 * and the only way to put anything on it was to type a domain into Settings —
 * which is the wrong end of the problem, since the mail you want rid of is the
 * one on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BlockSenderTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: BlocklistRepository
    private var inbox = 0L

    @Before
    fun open() = runBlocking {
        db = TestDatabase.open()
        repository = BlocklistRepository(db.blocklistDao())
        repository.seedIfEmpty()
        val accountId = db.accountDao().insert(TestDatabase.account())
        inbox = db.folderDao().insert(
            FolderEntity(accountId = accountId, path = "INBOX", displayName = "Inbox", type = "INBOX")
        )
    }

    @After
    fun close() = db.close()

    private suspend fun mailFrom(address: String) = db.messageDao().insert(
        MessageEntity(
            accountId = 1, folderId = inbox, uid = address.hashCode().toLong(),
            subject = "Offer", fromAddress = address,
            senderDomain = address.substringAfterLast('@').lowercase()
        )
    )

    @Test
    fun `blocking one address blocks that address`() = runBlocking {
        repository.blockSender("spam@example.com")

        assertTrue(repository.isBlocked("spam@example.com"))
    }

    @Test
    fun `blocking one address leaves the rest of the domain alone`() = runBlocking {
        repository.blockSender("spam@example.com")

        assertFalse(repository.isBlocked("colleague@example.com"))
    }

    @Test
    fun `blocking a domain blocks everyone at it`() = runBlocking {
        repository.blockSender("example.com")

        assertTrue(repository.isBlocked("anyone@example.com"))
        assertTrue(repository.isBlocked("someone.else@example.com"))
    }

    @Test
    fun `a blocked sender is drawn in red in the list`() = runBlocking {
        mailFrom("spam@example.com")
        mailFrom("friend@elsewhere.org")
        repository.blockSender("spam@example.com")

        val rows = db.messageDao().observeFolder(inbox, 50).first()

        assertEquals(1, rows.count { it.spam })
        assertTrue(rows.first { it.spam }.fromAddress == "spam@example.com")
    }

    @Test
    fun `blocking turns the manual list on, so it takes effect at once`() = runBlocking {
        repository.blockSender("spam@example.com")

        val manual = db.blocklistDao().manualList()
        assertTrue(manual!!.enabled)
        assertEquals(1, manual.entryCount)
    }

    @Test
    fun `a blocked sender can be taken back off the list`() = runBlocking {
        repository.blockSender("spam@example.com")
        repository.unblockSender("spam@example.com")

        assertFalse(repository.isBlocked("spam@example.com"))
    }

    @Test
    fun `blocking the same sender twice does not double the entry`() = runBlocking {
        repository.blockSender("spam@example.com")
        repository.blockSender("spam@example.com")

        assertEquals(1, db.blocklistDao().manualList()!!.entryCount)
    }

    @Test
    fun `nothing is deleted or hidden by blocking`() = runBlocking {
        val id = mailFrom("spam@example.com")

        repository.blockSender("spam@example.com")

        assertEquals(1, db.messageDao().observeFolder(inbox, 50).first().size)
        assertTrue(db.messageDao().get(id) != null)
    }
}
