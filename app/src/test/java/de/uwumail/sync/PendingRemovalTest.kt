package de.uwumail.sync

import androidx.test.core.app.ApplicationProvider
import de.uwumail.data.crypto.CredentialStore
import de.uwumail.data.db.AppDatabase
import de.uwumail.data.db.FolderEntity
import de.uwumail.data.db.MessageEntity
import de.uwumail.data.db.TestDatabase
import de.uwumail.mail.ImapPool
import de.uwumail.mail.SmtpSender
import de.uwumail.mail.oauth.OAuthClient
import de.uwumail.mail.oauth.OAuthConfig
import de.uwumail.mail.oauth.TokenStore
import de.uwumail.notify.Notifier
import de.uwumail.rules.RuleEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * A row hidden for a removal that never happened must come back.
 *
 * Rows are hidden the moment an archive or a delete starts, and put back only
 * when the server refuses. Work that succeeds by doing nothing at all — moving
 * a message into the folder it is already in — went down neither path, and left
 * the mail present in the database but invisible in every list until the process
 * next started.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PendingRemovalTest {

    private lateinit var db: AppDatabase
    private lateinit var sync: SyncManager

    private var accountId = 0L
    private var inbox = 0L
    private var archive = 0L

    @Before
    fun open() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = TestDatabase.open()

        val credentials = CredentialStore(context)
        val tokens = TokenStore(db.accountDao(), credentials, OAuthClient(), OAuthConfig(context))
        sync = SyncManager(
            context = context,
            db = db,
            pool = ImapPool(db.accountDao(), tokens),
            credentials = credentials,
            tokenStore = tokens,
            ruleEngine = RuleEngine(db.ruleDao()),
            notifier = Notifier(context),
            smtp = SmtpSender()
        )

        accountId = db.accountDao().insert(TestDatabase.account())
        inbox = db.folderDao().insert(
            FolderEntity(accountId = accountId, path = "INBOX", displayName = "Inbox", type = "INBOX")
        )
        archive = db.folderDao().insert(
            FolderEntity(
                accountId = accountId, path = "Archive", displayName = "Archive", type = "ARCHIVE"
            )
        )
        db.accountDao().update(
            db.accountDao().get(accountId)!!.copy(archiveFolder = "Archive", trashFolder = "Trash")
        )
    }

    @After
    fun close() = db.close()

    private suspend fun message(folderId: Long, uid: Long = 1): Long = db.messageDao().insert(
        MessageEntity(
            accountId = accountId,
            folderId = folderId,
            uid = uid,
            subject = "Already filed",
            receivedAt = uid
        )
    )

    @Test
    fun `archiving a message already in the archive leaves it visible`() = runBlocking {
        val id = message(archive)

        sync.archive(listOf(id), allowUndo = false)

        assertFalse(db.messageDao().get(id)!!.pendingRemoval)
        assertEquals(1, db.messageDao().observeFolder(archive, 50).first().size)
    }

    @Test
    fun `the archive folder still counts a message that did not move`() = runBlocking {
        val id = message(archive)

        sync.archive(listOf(id), allowUndo = false)

        assertEquals(1, db.folderDao().get(archive)!!.totalCount)
    }

    /**
     * A move is offered with an undo, so the work waits a few seconds first.
     * Nothing here is testing the wait; flushing is what leaving the screen
     * does, and then the outcome is the same one every other action reaches.
     */
    private suspend fun runPendingWork(id: Long) {
        sync.flushPendingRemovals()
        withTimeout(5_000) {
            while (db.messageDao().get(id)?.pendingRemoval != false) delay(10)
        }
    }

    @Test
    fun `moving a message into the folder it is already in leaves it visible`() = runBlocking {
        val id = message(inbox)

        sync.moveMessages(listOf(id), inbox)
        runPendingWork(id)

        assertFalse(db.messageDao().get(id)!!.pendingRemoval)
        assertEquals(1, db.messageDao().observeFolder(inbox, 50).first().size)
    }

    @Test
    fun `a removal taken back puts its row straight back`() = runBlocking {
        val id = message(inbox)
        val offers = mutableListOf<Undoable>()
        val watching = launch { sync.undoable.collect { offers += it } }

        sync.archive(listOf(id))
        withTimeout(5_000) { while (offers.isEmpty()) delay(10) }
        assertTrue(sync.undo(offers.first().token))
        watching.cancel()

        assertFalse(db.messageDao().get(id)!!.pendingRemoval)
    }

    @Test
    fun `a message elsewhere is untouched by an archive that did nothing`() = runBlocking {
        val filed = message(archive, uid = 1)
        val waiting = message(inbox, uid = 2)

        sync.archive(listOf(filed), allowUndo = false)

        assertFalse(db.messageDao().get(waiting)!!.pendingRemoval)
    }
}
