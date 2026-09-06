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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Marking a message replied to.
 *
 * `\Answered` is what every mail client draws its reply arrow from. Nothing in
 * the app ever set it: the flag was read from the server, stored, carried into
 * the list summary and then never written or drawn, so a thread answered from
 * uwuMail looked untouched here and everywhere else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AnsweredFlagTest {

    private lateinit var db: AppDatabase
    private lateinit var sync: SyncManager

    private var accountId = 0L
    private var inbox = 0L

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
    }

    @After
    fun close() = db.close()

    private suspend fun message(uid: Long = 1) = db.messageDao().insert(
        MessageEntity(
            accountId = accountId, folderId = inbox, uid = uid, subject = "Question", receivedAt = uid
        )
    )

    @Test
    fun `a message starts out unanswered`() = runBlocking {
        assertFalse(db.messageDao().get(message())!!.answered)
    }

    @Test
    fun `replying marks the message answered`() = runBlocking {
        val id = message()

        sync.setAnswered(listOf(id))

        assertTrue(db.messageDao().get(id)!!.answered)
    }

    @Test
    fun `the list row carries the flag so it can be drawn`() = runBlocking {
        val id = message()

        sync.setAnswered(listOf(id))

        assertTrue(db.messageDao().observeFolder(inbox, 50).first().first().answered)
    }

    @Test
    fun `only the message replied to is marked`() = runBlocking {
        val replied = message(uid = 1)
        val other = message(uid = 2)

        sync.setAnswered(listOf(replied))

        assertTrue(db.messageDao().get(replied)!!.answered)
        assertFalse(db.messageDao().get(other)!!.answered)
    }

    @Test
    fun `the flag can be taken off again`() = runBlocking {
        val id = message()

        sync.setAnswered(listOf(id))
        sync.setAnswered(listOf(id), answered = false)

        assertFalse(db.messageDao().get(id)!!.answered)
    }

    @Test
    fun `a server that cannot be reached still marks it here`() = runBlocking {
        // There is no server in these tests; the local flag must still be set,
        // and the next flag sync will carry it either way.
        val id = message()

        sync.setAnswered(listOf(id))

        assertTrue(db.messageDao().get(id)!!.answered)
    }
}
