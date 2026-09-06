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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mail the server would not part with stays here.
 *
 * A permanent delete used to drop the row whether or not the server agreed,
 * which left the message sitting in the mailbox and gone from the app — and
 * gone for good, since its UID is far below the folder's high-water mark and
 * no later sync would fetch it again.
 *
 * There is no server in these tests, which is exactly the failure being
 * described: every removal below is one that could not be carried out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RemovalFailureTest {

    private lateinit var db: AppDatabase
    private lateinit var sync: SyncManager

    private var accountId = 0L
    private var inbox = 0L
    private var device = 0L

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
        device = db.folderDao().insert(
            FolderEntity(
                accountId = accountId,
                path = "local/Keepsakes",
                displayName = "Keepsakes",
                type = "LOCAL",
                isLocal = true
            )
        )
    }

    @After
    fun close() = db.close()

    private suspend fun message(folderId: Long, local: Boolean = false): Long =
        db.messageDao().insert(
            MessageEntity(
                accountId = accountId,
                folderId = folderId,
                uid = if (local) -1 else 1,
                subject = "Please keep me",
                isLocal = local,
                receivedAt = 1
            )
        )

    @Test
    fun `a delete the server refused keeps the mail`() = runBlocking {
        val id = message(inbox)

        runCatching { sync.deletePermanently(listOf(id), allowUndo = false) }

        assertNotNull(db.messageDao().get(id))
    }

    @Test
    fun `mail kept after a refused delete is visible again`() = runBlocking {
        val id = message(inbox)

        runCatching { sync.deletePermanently(listOf(id), allowUndo = false) }

        assertFalse(db.messageDao().get(id)!!.pendingRemoval)
        assertEquals(1, db.messageDao().observeFolder(inbox, 50).first().size)
    }

    @Test
    fun `a refused delete is reported rather than passing quietly`() = runBlocking {
        val id = message(inbox)

        val outcome = runCatching { sync.deletePermanently(listOf(id), allowUndo = false) }

        assertEquals(true, outcome.isFailure)
    }

    @Test
    fun `trashing with no trash folder keeps mail the server would not remove`() = runBlocking {
        val id = message(inbox)

        runCatching { sync.moveToTrash(listOf(id), allowUndo = false) }

        assertNotNull(db.messageDao().get(id))
    }

    @Test
    fun `a device-only message needs no server to agree`() = runBlocking {
        val id = message(device, local = true)

        sync.deletePermanently(listOf(id), allowUndo = false)

        assertNull(db.messageDao().get(id))
    }

    @Test
    fun `the folder still counts mail that was not removed`() = runBlocking {
        val id = message(inbox)

        runCatching { sync.deletePermanently(listOf(id), allowUndo = false) }

        assertEquals(1, db.folderDao().get(inbox)!!.totalCount)
    }
}
