package de.uwumail.data.repo

import android.content.Context
import de.uwumail.core.Security
import de.uwumail.data.crypto.CredentialStore
import de.uwumail.data.db.AccountEntity
import de.uwumail.data.db.AppDatabase
import de.uwumail.data.db.IdentityEntity
import de.uwumail.mail.ImapClient
import de.uwumail.mail.ImapPool
import de.uwumail.mail.oauth.AuthType
import de.uwumail.mail.oauth.TokenSet
import de.uwumail.mail.oauth.TokenStore
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

/** What [AccountRepository.saveIdentity] actually did. */
enum class IdentitySaveResult { CREATED, UPDATED, UNCHANGED }

class AccountRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val credentials: CredentialStore,
    private val pool: ImapPool,
    private val tokenStore: TokenStore,
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
        smtpPassword: String?,
        /** Present when the account was just created or re-authorised via OAuth. */
        oauthTokens: TokenSet? = null
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
        oauthTokens?.let { tokenStore.store(id, it) }

        if (account.id == 0L) {
            db.identityDao().insert(
                IdentityEntity(
                    accountId = id,
                    displayName = account.displayName.trim(),
                    email = account.email.trim().lowercase(),
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
        tokenStore.clear(accountId)
        credentials.removeAccount(accountId)
        notifier.removeAccountChannels(accountId)
        db.accountDao().delete(accountId)
        PushService.restart(context, db.accountDao().getAll().any { it.pushEnabled })
    }

    suspend fun imapPassword(accountId: Long) = credentials.get(credentials.imapKey(accountId))
    suspend fun smtpPassword(accountId: Long) = credentials.get(credentials.smtpKey(accountId))

    /** The secret SMTP should authenticate with: a password, or a live access token. */
    suspend fun smtpSecret(account: AccountEntity): String = tokenStore.smtpSecret(account)

    fun isSignedIn(accountId: Long) = tokenStore.hasRefreshToken(accountId)

    // -------------------------------------------------------------- identities

    /**
     * Saves an identity, folding it into an existing one for the same address.
     *
     * Returns [IdentitySaveResult.UNCHANGED] when an identical entry is already
     * there, so callers can say so instead of silently writing a duplicate — the
     * unique index would reject it anyway.
     */
    suspend fun saveIdentity(identity: IdentityEntity): IdentitySaveResult {
        val email = identity.email.trim().lowercase()
        require(email.isNotEmpty()) { "An identity needs an address" }
        val candidate = identity.copy(email = email, displayName = identity.displayName.trim())

        val existing = db.identityDao().forAccount(candidate.accountId)
            .firstOrNull { it.email == email && it.id != candidate.id }

        if (existing != null &&
            existing.displayName == candidate.displayName &&
            existing.replyTo == candidate.replyTo &&
            existing.signature == candidate.signature &&
            (!candidate.isDefault || existing.isDefault)
        ) {
            return IdentitySaveResult.UNCHANGED
        }

        if (candidate.isDefault) db.identityDao().clearDefault(candidate.accountId)

        return when {
            existing != null -> {
                db.identityDao().update(
                    existing.copy(
                        displayName = candidate.displayName,
                        replyTo = candidate.replyTo,
                        signature = candidate.signature,
                        isDefault = candidate.isDefault || existing.isDefault
                    )
                )
                IdentitySaveResult.UPDATED
            }
            candidate.id == 0L -> {
                db.identityDao().insert(candidate)
                IdentitySaveResult.CREATED
            }
            else -> {
                db.identityDao().update(candidate)
                IdentitySaveResult.UPDATED
            }
        }
    }

    suspend fun deleteIdentity(identity: IdentityEntity) = db.identityDao().delete(identity)

    // -------------------------------------------------------------- connection

    /** Verifies IMAP login and, separately, SMTP login. Returns a readable failure. */
    suspend fun testConnection(
        account: AccountEntity,
        imapPassword: String,
        smtpPassword: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val oauth = runCatching { AuthType.valueOf(account.authType) }
            .getOrDefault(AuthType.PASSWORD) == AuthType.OAUTH2
        val imapSecret: String
        val smtpSecret: String
        if (oauth) {
            val token = runCatching { tokenStore.imapSecret(account) }
                .getOrElse { return@withContext Result.failure(it) }
            imapSecret = token
            smtpSecret = token
        } else {
            imapSecret = imapPassword
            smtpSecret = smtpPassword
        }

        val imapFolders = try {
            ImapClient(account, imapSecret).use { client ->
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
                if (oauth) {
                    put("mail.smtp.auth.mechanisms", "XOAUTH2")
                    put("mail.smtp.auth.login.disable", "true")
                    put("mail.smtp.auth.plain.disable", "true")
                }
            }
            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() =
                    PasswordAuthentication(account.smtpUsername, smtpSecret)
            })
            val transport: Transport = session.getTransport("smtp")
            transport.connect(account.smtpHost, account.smtpPort, account.smtpUsername, smtpSecret)
            transport.close()
        } catch (e: Throwable) {
            return@withContext Result.failure(Exception("SMTP: ${e.message}", e))
        }

        Result.success("Connected. $imapFolders folders found.")
    }
}
