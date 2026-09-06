package de.uwumail.sync

import androidx.test.core.app.ApplicationProvider
import de.uwumail.data.crypto.CredentialStore
import de.uwumail.data.db.AppDatabase
import de.uwumail.data.db.FolderEntity
import de.uwumail.data.db.MessageEntity
import de.uwumail.data.db.OutboxEntity
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A message that could not go out is waiting, not lost.
 *
 * The outbox existed as a table, a DAO and a settings row, and nothing ever put
 * anything in it: a send attempted with no connection handed the error back to
 * the composer, and the message lived exactly as long as that screen did.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OutboxTest {

    private lateinit var db: AppDatabase
    private lateinit var sync: SyncManager

    private var accountId = 0L
    private var drafts = 0L

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
        drafts = db.folderDao().insert(
            FolderEntity(
                accountId = accountId, path = "Drafts", displayName = "Drafts", type = "DRAFTS"
            )
        )
    }

    @After
    fun close() = db.close()

    private fun queued(
        draftMessageId: Long? = null,
        answeringMessageId: Long? = null
    ) = OutboxEntity(
        accountId = accountId,
        identityId = null,
        fromAddress = "me@example.com",
        fromName = "Me",
        to = "you@example.com",
        cc = "",
        bcc = "",
        replyTo = null,
        subject = "Waiting",
        bodyPlain = "Body",
        bodyHtml = null,
        createdAt = 0,
        draftMessageId = draftMessageId,
        answeringMessageId = answeringMessageId
    )

    @Test
    fun `a queued message survives being written and read back`() = runBlocking {
        db.outboxDao().insert(queued())

        val waiting = db.outboxDao().pending()

        assertEquals(1, waiting.size)
        assertEquals("you@example.com", waiting.first().to)
    }

    @Test
    fun `a queued message remembers the draft it grew from`() = runBlocking {
        db.outboxDao().insert(queued(draftMessageId = 77L))

        assertEquals(77L, db.outboxDao().pending().first().draftMessageId)
    }

    @Test
    fun `a send that cannot reach the server leaves the message queued`() = runBlocking {
        db.outboxDao().insert(queued())

        val sent = sync.sendOutbox()

        assertEquals(0, sent)
        assertEquals(1, db.outboxDao().pending().size)
    }

    @Test
    fun `a failed attempt is counted and its reason kept`() = runBlocking {
        db.outboxDao().insert(queued())

        sync.sendOutbox()

        val item = db.outboxDao().pending().first()
        assertEquals(1, item.attempts)
        assertNotNull(item.lastError)
    }

    @Test
    fun `a message that could not be sent keeps its draft`() = runBlocking {
        val draft = db.messageDao().insert(
            MessageEntity(accountId = accountId, folderId = drafts, uid = 1, subject = "Half written")
        )
        db.outboxDao().insert(queued(draftMessageId = draft))

        sync.sendOutbox()

        assertNotNull(db.messageDao().get(draft))
    }

    @Test
    fun `retrying counts a second attempt rather than queueing twice`() = runBlocking {
        db.outboxDao().insert(queued())

        sync.sendOutbox()
        sync.sendOutbox()

        val waiting = db.outboxDao().pending()
        assertEquals(1, waiting.size)
        assertEquals(2, waiting.first().attempts)
    }

    @Test
    fun `an account that no longer exists does not stall the rest of the queue`() = runBlocking {
        db.outboxDao().insert(queued().copy(accountId = 9999))
        db.outboxDao().insert(queued())

        sync.sendOutbox()

        // The orphan is left alone; the real one was tried and counted.
        val waiting = db.outboxDao().pending()
        assertEquals(2, waiting.size)
        assertTrue(waiting.any { it.attempts == 1 })
    }

    @Test
    fun `a queued reply remembers what it is answering`() = runBlocking {
        db.outboxDao().insert(queued(answeringMessageId = 42L))

        assertEquals(42L, db.outboxDao().pending().first().answeringMessageId)
    }

    @Test
    fun `a reply that has not gone out yet does not flag the original`() = runBlocking {
        val original = db.messageDao().insert(
            MessageEntity(accountId = accountId, folderId = drafts, uid = 5, subject = "Question")
        )
        db.outboxDao().insert(queued(answeringMessageId = original))

        sync.sendOutbox()

        assertEquals(false, db.messageDao().get(original)!!.answered)
    }

    @Test
    fun `the waiting count is what the settings screen reads`() = runBlocking {
        db.outboxDao().insert(queued())
        db.outboxDao().insert(queued())

        assertEquals(2, db.outboxDao().pending().size)
    }
}
