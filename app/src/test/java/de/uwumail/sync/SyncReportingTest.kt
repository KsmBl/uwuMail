package de.uwumail.sync

import androidx.test.core.app.ApplicationProvider
import de.uwumail.core.FolderType
import de.uwumail.data.crypto.CredentialStore
import de.uwumail.data.db.AppDatabase
import de.uwumail.data.db.FolderEntity
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A sync that cannot reach the server has to say so.
 *
 * The unified views caught every failure and dropped it, at three separate
 * depths — the account, the folder, and the fetch — and wrote the reason to a
 * field no screen read. Pulling down on *All inboxes* with no way to reach the
 * server therefore turned the spinner and put back exactly what was there
 * before, which is indistinguishable from having no new mail. On a phone that
 * fetches nothing at all, that is the difference between a bug report and a
 * fixable one.
 *
 * There is no server in these tests, so every sync below is one that failed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncReportingTest {

    private lateinit var db: AppDatabase
    private lateinit var sync: SyncManager

    private var accountId = 0L

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
        accountId = db.accountDao().insert(TestDatabase.account("nobody@example.com"))
        db.folderDao().insert(
            FolderEntity(
                accountId = accountId,
                path = "INBOX",
                displayName = "Inbox",
                type = "INBOX"
            )
        )
        Unit
    }

    @After
    fun close() = db.close()

    @Test
    fun `a unified view that could not fetch anything says so`() = runBlocking {
        val failure = runCatching { sync.syncUnified(FolderType.INBOX) }.exceptionOrNull()

        assertNotNull("syncUnified swallowed the failure", failure)
    }

    /** Which mailbox failed is the first thing worth knowing with several set up. */
    @Test
    fun `the failure names the account it belongs to`() = runBlocking {
        val failure = runCatching { sync.syncUnified(FolderType.INBOX) }.exceptionOrNull()

        assertTrue(
            "expected the address in: ${failure?.message}",
            failure?.message.orEmpty().contains("nobody@example.com")
        )
    }

    /** "Error: null" is worse than saying nothing; there is always a reason. */
    @Test
    fun `the failure is never blank`() = runBlocking {
        val failure = runCatching { sync.syncUnified(FolderType.INBOX) }.exceptionOrNull()

        val message = failure?.message.orEmpty().substringAfter("nobody@example.com:").trim()
        assertTrue("no reason given: ${failure?.message}", message.isNotBlank())
        assertFalse(message.contains("null"))
    }

    @Test
    fun `every account that failed is named, not just the first`() = runBlocking {
        val second = db.accountDao().insert(TestDatabase.account("someone@example.org"))
        db.folderDao().insert(
            FolderEntity(
                accountId = second,
                path = "INBOX",
                displayName = "Inbox",
                type = "INBOX"
            )
        )

        val failure = runCatching { sync.syncUnified(FolderType.INBOX) }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("nobody@example.com"))
        assertTrue(failure?.message.orEmpty().contains("someone@example.org"))
    }

    /** With no account there is nothing to reach, and nothing to complain about. */
    @Test
    fun `a unified view with no accounts is quiet`() = runBlocking {
        db.accountDao().delete(accountId)

        sync.syncUnified(FolderType.INBOX)
    }

    /**
     * The background check has no screen to fail on, so what it hit is left
     * where the list can pick it up.
     */
    @Test
    fun `a background check leaves the reason behind it`() = runBlocking {
        sync.syncAll()

        val recorded = sync.state.first().lastError
        assertNotNull("syncAll recorded nothing", recorded)
        assertTrue(recorded.orEmpty().contains("nobody@example.com"))
    }

    @Test
    fun `syncing one folder still throws to the caller that asked`() = runBlocking {
        val inbox = db.folderDao().forAccount(accountId).first { it.path == "INBOX" }

        val failure = runCatching { sync.syncFolder(inbox.id) }.exceptionOrNull()

        assertNotNull(failure)
    }

    /** A folder that is gone is not a failure; there is simply nothing to do. */
    @Test
    fun `syncing a folder that no longer exists is quiet`() = runBlocking {
        sync.syncFolder(999_999L)
    }
}
