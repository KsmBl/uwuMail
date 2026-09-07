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
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Emptying the bin.
 *
 * Mail could be deleted for good one message, one swipe or one selection at a
 * time, and never all of it — which is the only thing anybody actually wants
 * from a trash folder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EmptyBinTest {

    private lateinit var db: AppDatabase
    private lateinit var sync: SyncManager

    private var accountId = 0L
    private var bin = 0L
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
        bin = db.folderDao().insert(
            FolderEntity(accountId = accountId, path = "Trash", displayName = "Trash", type = "TRASH")
        )
        device = db.folderDao().insert(
            FolderEntity(
                accountId = accountId, path = "local/Kept", displayName = "Kept",
                type = "LOCAL", isLocal = true
            )
        )
    }

    @After
    fun close() = db.close()

    private suspend fun put(folderId: Long, uid: Long, local: Boolean = false) =
        db.messageDao().insert(
            MessageEntity(
                accountId = accountId, folderId = folderId, uid = if (local) -uid else uid,
                subject = "Message $uid", isLocal = local, receivedAt = uid
            )
        )

    @Test
    fun `emptying a device bin takes everything in it`() = runBlocking {
        // Device mail needs no server to agree, so it goes on the spot.
        put(device, 1, local = true)
        put(device, 2, local = true)

        assertEquals(2, sync.emptyFolder(device))
        assertEquals(0, db.messageDao().idsIn(device).size)
    }

    @Test
    fun `emptying reports how many went`() = runBlocking {
        repeat(5) { put(device, it + 1L, local = true) }

        assertEquals(5, sync.emptyFolder(device))
    }

    @Test
    fun `an empty bin reports nothing to do`() = runBlocking {
        assertEquals(0, sync.emptyFolder(bin))
    }

    @Test
    fun `emptying leaves every other folder alone`() = runBlocking {
        put(inbox, 1)
        put(device, 2, local = true)

        sync.emptyFolder(device)

        assertEquals(1, db.messageDao().idsIn(inbox).size)
    }

    @Test
    fun `mail the server would not part with is kept rather than forgotten`() = runBlocking {
        // There is no server in these tests, so the removal cannot go through.
        val id = put(bin, 1)

        runCatching { sync.emptyFolder(bin) }

        assertNotNull(db.messageDao().get(id))
    }

    @Test
    fun `the folder's count is right again afterwards`() = runBlocking {
        put(device, 1, local = true)
        db.folderDao().refreshCounts(device)

        sync.emptyFolder(device)

        assertEquals(0, db.folderDao().get(device)!!.totalCount)
    }
}
