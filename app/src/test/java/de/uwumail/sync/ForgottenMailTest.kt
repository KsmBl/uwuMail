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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * A notification must not outlive its message.
 *
 * A test mail arrived in the inbox, uwuMail fetched it and announced it, and
 * GMX's own spam filter then moved it out of the inbox a moment later. The next
 * sync did the right thing and dropped the local row — and left the
 * notification standing, so the only trace of the message anywhere was an
 * announcement that opened nothing and an inbox that did not contain it.
 *
 * Whatever the reason a message stops being in a folder, the announcement of it
 * stops with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ForgottenMailTest {

    private lateinit var db: AppDatabase
    private lateinit var sync: SyncManager
    private lateinit var notifier: Notifier

    private var accountId = 0L
    private var inbox = 0L

    @Before
    fun open() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = TestDatabase.open()
        val credentials = CredentialStore(context)
        val tokens = TokenStore(db.accountDao(), credentials, OAuthClient(), OAuthConfig(context))
        notifier = Notifier(context)
        sync = SyncManager(
            context = context,
            db = db,
            pool = ImapPool(db.accountDao(), tokens),
            credentials = credentials,
            tokenStore = tokens,
            ruleEngine = RuleEngine(db.ruleDao()),
            notifier = notifier,
            smtp = SmtpSender()
        )
        accountId = db.accountDao().insert(TestDatabase.account())
        inbox = db.folderDao().insert(
            FolderEntity(accountId = accountId, path = "INBOX", displayName = "Inbox", type = "INBOX")
        )
        Unit
    }

    @After
    fun close() = db.close()

    private suspend fun announced(uid: Long): Long {
        val id = db.messageDao().insert(
            MessageEntity(
                accountId = accountId,
                folderId = inbox,
                uid = uid,
                subject = "Test",
                fromAddress = "me@example.com",
                receivedAt = uid,
                notified = true
            )
        )
        val account = db.accountDao().get(accountId)!!
        notifier.notifyNewMail(
            account,
            db.messageDao().get(id)!!,
            de.uwumail.notify.NotificationPriority.DEFAULT
        )
        return id
    }

    /** What is actually in the shade, which is the thing the bug was about. */
    private fun standing(): Int =
        Shadows.shadowOf(
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSystemService(android.app.NotificationManager::class.java)
        ).size()

    @Test
    fun `announcing a message puts it in the shade`() = runBlocking {
        announced(uid = 1)

        assertEquals(1, standing())
    }

    /** The server moved it out of the inbox; the row goes, and so must the shade. */
    @Test
    fun `a message that left the folder takes its notification with it`() = runBlocking {
        announced(uid = 1)

        sync.forget(db.folderDao().get(inbox)!!, listOf(1L), "the server moved it")

        assertEquals("the notification outlived its message", 0, standing())
    }

    @Test
    fun `and the row goes too`() = runBlocking {
        announced(uid = 1)

        sync.forget(db.folderDao().get(inbox)!!, listOf(1L), "the server moved it")

        assertNull(db.messageDao().getByUid(inbox, 1))
    }

    @Test
    fun `mail that is still here keeps its notification`() = runBlocking {
        announced(uid = 1)
        announced(uid = 2)

        sync.forget(db.folderDao().get(inbox)!!, listOf(1L), "the server moved it")

        assertEquals(1, standing())
        assertNull(db.messageDao().getByUid(inbox, 1))
        assertEquals(2L, db.messageDao().getByUid(inbox, 2)?.uid)
    }

    /**
     * The line that gathers an account's notifications counted mail that was no
     * longer in the shade. The function written to take it away existed and had
     * never been called from anywhere.
     */
    @Test
    fun `the group line goes when the last message under it does`() = runBlocking {
        announced(uid = 1)
        announced(uid = 2)
        val account = db.accountDao().get(accountId)!!
        notifier.postSummary(account, 2)
        assertEquals(3, standing())

        sync.forget(db.folderDao().get(inbox)!!, listOf(1L, 2L), "the server moved them")

        assertEquals(0, standing())
    }

    @Test
    fun `and stays while there is still more than one under it`() = runBlocking {
        announced(uid = 1)
        announced(uid = 2)
        announced(uid = 3)
        val account = db.accountDao().get(accountId)!!
        notifier.postSummary(account, 3)

        sync.forget(db.folderDao().get(inbox)!!, listOf(1L), "the server moved it")

        // Two messages and the line gathering them.
        assertEquals(3, standing())
    }

    @Test
    fun `forgetting nothing does nothing`() = runBlocking {
        announced(uid = 1)

        sync.forget(db.folderDao().get(inbox)!!, emptyList(), "nothing to do")

        assertEquals(1, standing())
    }
}
