package de.uwumail.sync

import androidx.test.core.app.ApplicationProvider
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
 * Which folders are allowed to announce new mail.
 *
 * It used to be the inbox and nothing else, hardcoded. A mailbox whose server
 * files mail with sieve never sees its inbox, so uwuMail simply went quiet for
 * it — the folders were synced, the rules ran, and nothing was ever said.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FolderNotifyTest {

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
        accountId = db.accountDao().insert(TestDatabase.account())
    }

    @After
    fun close() = db.close()

    private suspend fun folder(
        path: String,
        type: String = "CUSTOM",
        notify: Boolean = false,
        synced: Boolean = false
    ) = db.folderDao().insert(
        FolderEntity(
            accountId = accountId, path = path, displayName = path, type = type,
            notify = notify, syncEnabled = synced
        )
    )

    @Test
    fun `a folder starts quiet`() = runBlocking {
        assertFalse(db.folderDao().get(folder("Invoices"))!!.notify)
    }

    @Test
    fun `switching notifications on sticks`() = runBlocking {
        val id = folder("Invoices")

        sync.setFolderNotify(id, true)

        assertTrue(db.folderDao().get(id)!!.notify)
    }

    @Test
    fun `switching them on also starts checking the folder`() = runBlocking {
        // A folder nobody checks unattended has no new mail to announce.
        val id = folder("Invoices", synced = false)

        val syncTurnedOn = sync.setFolderNotify(id, true)

        assertTrue(syncTurnedOn)
        assertTrue(db.folderDao().get(id)!!.syncEnabled)
    }

    @Test
    fun `a folder already checked needs no help`() = runBlocking {
        val id = folder("Invoices", synced = true)

        assertFalse(sync.setFolderNotify(id, true))
    }

    @Test
    fun `switching notifications off leaves the checking alone`() = runBlocking {
        val id = folder("Invoices", notify = true, synced = true)

        sync.setFolderNotify(id, false)

        val folder = db.folderDao().get(id)!!
        assertFalse(folder.notify)
        assertTrue(folder.syncEnabled)
    }

    @Test
    fun `one folder's setting does not touch another's`() = runBlocking {
        val invoices = folder("Invoices")
        val receipts = folder("Receipts")

        sync.setFolderNotify(invoices, true)

        assertFalse(db.folderDao().get(receipts)!!.notify)
    }

    @Test
    fun `a device folder is not put on background sync it cannot use`() = runBlocking {
        // Made the way createLocalFolder makes them: never background-synced.
        val id = db.folderDao().insert(
            FolderEntity(
                accountId = accountId, path = "local/Kept", displayName = "Kept",
                type = "LOCAL", isLocal = true, syncEnabled = false
            )
        )

        assertFalse(sync.setFolderNotify(id, true))
        assertFalse(db.folderDao().get(id)!!.syncEnabled)
    }
}
