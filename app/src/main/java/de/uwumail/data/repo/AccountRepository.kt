package de.uwumail.data.repo

import android.content.Context
import de.uwumail.core.Security
import de.uwumail.data.crypto.CredentialStore
import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.AppDatabase
import de.uwumail.data.db.IdentityEntity
import de.uwumail.mail.ImapClient
import de.uwumail.mail.ImapPool
import de.uwumail.notify.Notifier
import de.uwumail.sync.PushService
import de.uwumail.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport

class AccountRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val credentials: CredentialStore,
    private val pool: ImapPool,
    private val notifier: Notifier
) {

    fun observeAccounts() = db.accountDao().observeAll()
    fun observeAccountsWithIdentities() = db.accountDao().observeWithIdentities()
    fun observeIdentities(accountId: Long) = db.identityDao().observeForAccount(accountId)
    fun observeAllIdentities() = db.identityDao().observeAll()

    suspend fun get(id: Long) = db.accountDao().get(id)

    suspend fun save(
        account: AccountEntity,
        imapPassword: String?,
        smtpPassword: String?
    ): Long {
        val id = if (account.id == 0L) {
            val position = db.accountDao().count()
            db.accountDao().insert(account.copy(position = position))
        } else {
            db.accountDao().update(account)
            account.id
        }
        imapPassword?.takeIf { it.isNotEmpty() }
            ?.let { credentials.put(credentials.imapKey(id), it) }
        smtpPassword?.takeIf { it.isNotEmpty() }
            ?.let { credentials.put(credentials.smtpKey(id), it) }

        if (account.id == 0L) {
            db.identityDao().insert(
                IdentityEntity(
                    accountId = id,
                    displayName = account.displayName,
                    email = account.email,
                    isDefault = true
                )
            )
        }
        pool.evict(id)
        val stored = db.accountDao().get(id) ?: return id
        notifier.ensureChannels(listOf(stored))
        SyncScheduler.schedule(context, stored)
        PushService.restart(context, db.accountDao().getAll().any { it.pushEnabled })
        return id
    }

    suspend fun delete(accountId: Long) {
        SyncScheduler.cancel(context, accountId)
        pool.evict(accountId)
        credentials.removeAccount(accountId)
        notifier.removeAccountChannels(accountId)
        db.accountDao().delete(accountId)
        PushService.restart(context, db.accountDao().getAll().any { it.pushEnabled })
    }

    suspend fun imapPassword(accountId: Long) = credentials.get(credentials.imapKey(accountId))
    suspend fun smtpPassword(accountId: Long) = credentials.get(credentials.smtpKey(accountId))

    // -------------------------------------------------------------- identities

    suspend fun saveIdentity(identity: IdentityEntity) {
        if (identity.isDefault) db.identityDao().clearDefault(identity.accountId)
        if (identity.id == 0L) db.identityDao().insert(identity)
        else db.identityDao().update(identity)
    }

    suspend fun deleteIdentity(identity: IdentityEntity) = db.identityDao().delete(identity)

    // -------------------------------------------------------------- connection

    /** Verifies IMAP login and, separately, SMTP login. Returns a readable failure. */
    suspend fun testConnection(
        account: AccountEntity,
        imapPassword: String,
        smtpPassword: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val imapFolders = try {
            ImapClient(account, imapPassword).use { client ->
                client.connect()
                client.listFolders().size
            }
        } catch (e: Throwable) {
            return@withContext Result.failure(Exception("IMAP: ${e.message}", e))
        }

        try {
            val security = runCatching { Security.valueOf(account.smtpSecurity) }
                .getOrDefault(Security.STARTTLS)
            val props = Properties().apply {
                put("mail.smtp.host", account.smtpHost)
                put("mail.smtp.port", account.smtpPort.toString())
                put("mail.smtp.auth", "true")
                put("mail.smtp.connectiontimeout", "20000")
                put("mail.smtp.timeout", "20000")
                when (security) {
                    Security.SSL_TLS -> put("mail.smtp.ssl.enable", "true")
                    Security.STARTTLS -> {
                        put("mail.smtp.starttls.enable", "true")
                        put("mail.smtp.starttls.required", "true")
                    }
                    Security.NONE -> Unit
                }
                if (account.trustAllCerts) put("mail.smtp.ssl.trust", "*")
            }
            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() =
                    PasswordAuthentication(account.smtpUsername, smtpPassword)
            })
            val transport: Transport = session.getTransport("smtp")
            transport.connect(account.smtpHost, account.smtpPort, account.smtpUsername, smtpPassword)
            transport.close()
        } catch (e: Throwable) {
            return@withContext Result.failure(Exception("SMTP: ${e.message}", e))
        }

        Result.success("Connected. $imapFolders folders found.")
    }
}
