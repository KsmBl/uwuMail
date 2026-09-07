package de.uwumail.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Marking folders as playing a role the server did not give them.
 *
 * A mailbox can have more than one folder doing the same job — two addresses
 * delivering into two inboxes, say — and the classifier only ever named one of
 * each. Several folders may now be marked the same, while where mail is *moved*
 * to stays one choice on the account, since a message can only go to one place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FolderRoleTest {

    private lateinit var db: AppDatabase
    private var accountId = 0L

    @Before
    fun open() = runBlocking {
        db = TestDatabase.open()
        accountId = db.accountDao().insert(TestDatabase.account())
    }

    @After
    fun close() = db.close()

    private suspend fun folder(
        path: String,
        type: String = "CUSTOM",
        role: String? = null
    ) = db.folderDao().insert(
        FolderEntity(
            accountId = accountId, path = path, displayName = path,
            type = type, roleOverride = role
        )
    )

    private suspend fun mail(folderId: Long, uid: Long) = db.messageDao().insert(
        MessageEntity(
            accountId = accountId, folderId = folderId, uid = uid,
            subject = "Message $uid", receivedAt = uid
        )
    )

    @Test
    fun `a folder with no marking plays what the server said`() = runBlocking {
        val id = folder("INBOX", type = "INBOX")

        assertEquals("INBOX", db.folderDao().get(id)!!.effectiveType)
    }

    @Test
    fun `a marking wins over the server`() = runBlocking {
        val id = folder("Newsletters", type = "CUSTOM", role = "INBOX")

        assertEquals("INBOX", db.folderDao().get(id)!!.effectiveType)
    }

    @Test
    fun `two folders marked as inboxes are both gathered`() = runBlocking {
        val real = folder("INBOX", type = "INBOX")
        val second = folder("Work", type = "CUSTOM", role = "INBOX")
        mail(real, 1)
        mail(second, 2)

        assertEquals(2, db.messageDao().observeUnified("INBOX", 50).first().size)
    }

    @Test
    fun `an unmarked folder is left out of the view`() = runBlocking {
        val real = folder("INBOX", type = "INBOX")
        val other = folder("Work", type = "CUSTOM")
        mail(real, 1)
        mail(other, 2)

        assertEquals(1, db.messageDao().observeUnified("INBOX", 50).first().size)
    }

    @Test
    fun `a folder can be marked out of a role the server gave it`() = runBlocking {
        // The server calls it Trash; here it is to count as nothing special.
        val id = folder("Trash", type = "TRASH", role = "CUSTOM")
        mail(id, 1)

        assertTrue(db.messageDao().observeUnified("TRASH", 50).first().isEmpty())
    }

    @Test
    fun `several folders can be marked as the bin`() = runBlocking {
        val one = folder("Trash", type = "TRASH")
        val two = folder("Deleted Items", type = "CUSTOM", role = "TRASH")
        mail(one, 1)
        mail(two, 2)

        assertEquals(2, db.messageDao().observeUnified("TRASH", 50).first().size)
    }

    @Test
    fun `a marked folder is the bin for a message in it`() = runBlocking {
        val account = db.accountDao().get(accountId)!!.copy(trashFolder = "Trash")
        val marked = db.folderDao().get(folder("Deleted Items", role = "TRASH"))!!

        assertTrue(marked.isBinFor(account))
    }

    @Test
    fun `the routing target stays a single choice`() = runBlocking {
        folder("Trash", type = "TRASH")
        folder("Deleted Items", type = "CUSTOM", role = "TRASH")
        db.accountDao().update(db.accountDao().get(accountId)!!.copy(trashFolder = "Trash"))

        // Two folders count as the bin; exactly one is where mail is moved to.
        assertEquals("Trash", db.accountDao().get(accountId)!!.trashFolder)
    }

    @Test
    fun `conversations gather across every folder in the role`() = runBlocking {
        val one = folder("INBOX", type = "INBOX")
        val two = folder("Work", type = "CUSTOM", role = "INBOX")
        db.messageDao().insert(
            MessageEntity(
                accountId = accountId, folderId = one, uid = 1,
                threadId = "<root@x>", subject = "Lunch?", receivedAt = 1
            )
        )
        db.messageDao().insert(
            MessageEntity(
                accountId = accountId, folderId = two, uid = 2,
                threadId = "<root@x>", subject = "Re: Lunch?", receivedAt = 2
            )
        )

        val rows = db.messageDao().observeUnifiedThreads("INBOX", 50).first()

        assertEquals(1, rows.size)
        assertEquals(2, rows.first().threadCount)
    }
}
