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
import de.uwumail.notify.NotificationPriority
import de.uwumail.notify.Notifier
import de.uwumail.rules.RuleEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Reading a message takes it out of the shade.
 *
 * It used to happen only as a side effect of marking the message read, which
 * ran after the body had been downloaded and only when the message was not
 * already flagged read. So a body that would not download left the
 * notification standing over the mail being read on screen, and so did a
 * message another client had already read — the commonest case of all, since
 * that is what a second mail client on a desktop does all day.
 *
 * Dismissing is its own operation now, touches nothing but the shade, and
 * cannot be skipped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReadClearsNotificationTest {

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

    private suspend fun announced(uid: Long, seen: Boolean = false): Long {
        val id = db.messageDao().insert(
            MessageEntity(
                accountId = accountId,
                folderId = inbox,
                uid = uid,
                subject = "Test $uid",
                fromAddress = "someone@example.com",
                receivedAt = uid,
                seen = seen,
                notified = true
            )
        )
        notifier.notifyNewMail(
            db.accountDao().get(accountId)!!,
            db.messageDao().get(id)!!,
            NotificationPriority.DEFAULT
        )
        return id
    }

    private fun standing(): Int =
        Shadows.shadowOf(
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .getSystemService(android.app.NotificationManager::class.java)
        ).size()

    @Test
    fun `reading a message clears its notification`() = runBlocking {
        val id = announced(uid = 1)

        sync.dismissNotifications(listOf(id))

        assertEquals(0, standing())
    }

    /**
     * The case that made this its own operation: already flagged read, so the
     * path through marking it read never ran.
     */
    @Test
    fun `a message already marked read still loses its notification`() = runBlocking {
        val id = announced(uid = 1, seen = true)

        sync.dismissNotifications(listOf(id))

        assertEquals(0, standing())
    }

    /** Nothing about the message itself changes; only the shade. */
    @Test
    fun `dismissing does not mark the message read`() = runBlocking {
        val id = announced(uid = 1)

        sync.dismissNotifications(listOf(id))

        assertFalse(db.messageDao().get(id)!!.seen)
    }

    /** It must stop being counted, or the line above it keeps counting it. */
    @Test
    fun `a dismissed message no longer stands in the shade`() = runBlocking {
        val id = announced(uid = 1)

        sync.dismissNotifications(listOf(id))

        assertFalse(db.messageDao().get(id)!!.notified)
        assertEquals(0, db.messageDao().standingNotifications(accountId))
    }

    @Test
    fun `other messages keep theirs`() = runBlocking {
        val first = announced(uid = 1)
        announced(uid = 2)

        sync.dismissNotifications(listOf(first))

        assertEquals(1, standing())
        assertEquals(1, db.messageDao().standingNotifications(accountId))
    }

    @Test
    fun `the gathering line goes with the last one under it`() = runBlocking {
        val first = announced(uid = 1)
        val second = announced(uid = 2)
        notifier.postSummary(db.accountDao().get(accountId)!!, 2)
        assertEquals(3, standing())

        sync.dismissNotifications(listOf(first, second))

        assertEquals(0, standing())
    }

    @Test
    fun `dismissing nothing does nothing`() = runBlocking {
        announced(uid = 1)

        sync.dismissNotifications(emptyList())

        assertEquals(1, standing())
    }
}
