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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * New mail is not shown until the rules have finished with it.
 *
 * A message a rule files elsewhere used to be written into the folder it
 * arrived in, drawn there, and taken out again once the IMAP round trip that
 * moved it came back — so mail bound for the bin appeared in the inbox for as
 * long as the server took to answer. It now goes in hidden, the way anything
 * on its way out of a folder does, and this is the behaviour that rests on:
 * a hidden row is in no list and no count, is still reachable by uid for the
 * notification and the local copy that need it, and comes back into view if
 * whatever was going to take it away never did.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RuleIngestVisibilityTest {

    private lateinit var db: AppDatabase
    private lateinit var sync: SyncManager

    private var accountId = 0L
    private var inbox = 0L
    private var spam = 0L
    private var keepsakes = 0L
    private var scrapbook = 0L

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
        spam = db.folderDao().insert(
            FolderEntity(accountId = accountId, path = "Spam", displayName = "Spam", type = "SPAM")
        )
        keepsakes = localFolder("Keepsakes")
        scrapbook = localFolder("Scrapbook")
    }

    @After
    fun close() = db.close()

    private suspend fun localFolder(name: String): Long = db.folderDao().insert(
        FolderEntity(
            accountId = accountId,
            path = "local/$name",
            displayName = name,
            type = "LOCAL",
            isLocal = true,
            syncEnabled = false
        )
    )

    /** A message written the way ingest writes one a rule is taking away. */
    private suspend fun arriving(
        folderId: Long,
        uid: Long = 1,
        hidden: Boolean = true
    ): Long = db.messageDao().insert(
        MessageEntity(
            accountId = accountId,
            folderId = folderId,
            uid = uid,
            subject = "Half price everything",
            fromAddress = "deals@example.com",
            receivedAt = uid,
            rulesApplied = true,
            pendingRemoval = hidden
        )
    )

    @Test
    fun `mail a rule is filing elsewhere is not in the list it arrived in`() = runBlocking {
        arriving(inbox)

        assertEquals(0, db.messageDao().observeFolder(inbox, 50).first().size)
        assertEquals(0, db.messageDao().observeFolderThreads(inbox, 50).first().size)
    }

    @Test
    fun `nor in the unified list of every inbox`() = runBlocking {
        arriving(inbox)

        assertEquals(0, db.messageDao().observeUnified("INBOX", 50).first().size)
        assertEquals(0, db.messageDao().observeUnifiedThreads("INBOX", 50).first().size)
    }

    @Test
    fun `nor in the folder's unread badge`() = runBlocking {
        arriving(inbox)
        db.folderDao().refreshCounts(inbox)

        assertEquals(0, db.folderDao().get(inbox)!!.unreadCount)
        assertEquals(0, db.folderDao().get(inbox)!!.totalCount)
    }

    /**
     * The row has to stay findable while it is hidden: the notification is
     * posted from it, and a rule that also copies the message to the device
     * downloads it from the folder it is still sitting in.
     */
    @Test
    fun `but the row is still there to be found by uid`() = runBlocking {
        arriving(inbox, uid = 7)

        assertNotNull(db.messageDao().getByUid(inbox, 7))
    }

    @Test
    fun `a move the server refused brings the mail back into the list`() = runBlocking {
        arriving(inbox, uid = 7)

        db.messageDao().clearPendingRemoval(inbox, listOf(7))

        assertEquals(1, db.messageDao().observeFolder(inbox, 50).first().size)
    }

    @Test
    fun `only the mail that did not move comes back`() = runBlocking {
        arriving(inbox, uid = 7)
        arriving(inbox, uid = 8)

        db.messageDao().clearPendingRemoval(inbox, listOf(7))

        assertEquals(listOf(7L), db.messageDao().observeFolder(inbox, 50).first().map { it.uid })
    }

    /** Uids are unique per folder, not per account: two folders share the number. */
    @Test
    fun `showing one folder's mail again leaves another folder's alone`() = runBlocking {
        arriving(inbox, uid = 7)
        arriving(spam, uid = 7)

        db.messageDao().clearPendingRemoval(inbox, listOf(7))

        assertEquals(0, db.messageDao().observeFolder(spam, 50).first().size)
    }

    /** A rule that copies to the device, on a message another rule is moving away. */
    @Test
    fun `a copy taken from a hidden row arrives visible`() = runBlocking {
        val id = onDevice(keepsakes, hidden = true)

        sync.copyToLocalFolder(listOf(id), scrapbook)

        assertEquals(1, db.messageDao().observeFolder(scrapbook, 50).first().size)
    }

    @Test
    fun `a hidden message moved to a local folder arrives visible`() = runBlocking {
        val id = onDevice(keepsakes, hidden = true)

        sync.moveToLocalFolder(listOf(id), scrapbook)

        val moved = db.messageDao().get(id)!!
        assertEquals(scrapbook, moved.folderId)
        assertFalse(moved.pendingRemoval)
        assertEquals(1, db.messageDao().observeFolder(scrapbook, 50).first().size)
    }

    /** A message already on the device, with the .eml the local paths expect. */
    private suspend fun onDevice(folderId: Long, hidden: Boolean): Long {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.filesDir, "local/$accountId/$folderId/-1.eml")
        file.parentFile?.mkdirs()
        file.writeText("Subject: Kept\r\n\r\nKept.\r\n")
        return db.messageDao().insert(
            MessageEntity(
                accountId = accountId,
                folderId = folderId,
                uid = -1,
                subject = "Kept",
                receivedAt = 1,
                isLocal = true,
                rawFilePath = file.absolutePath,
                pendingRemoval = hidden
            )
        )
    }
}
