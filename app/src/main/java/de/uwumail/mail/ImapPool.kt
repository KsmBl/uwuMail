package de.uwumail.mail

import de.uwumail.data.db.AccountDao
import de.uwumail.mail.oauth.AuthType
import de.uwumail.mail.oauth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps one warm IMAP connection per account and serialises access to it.
 *
 * IMAP is a single-command-at-a-time protocol, so every caller goes through the
 * account's mutex. A failed block tears the connection down, so the next caller
 * reconnects rather than inheriting a half-broken store.
 */
class ImapPool(
    private val accountDao: AccountDao,
    private val tokenStore: TokenStore
) {
    private val mutexes = ConcurrentHashMap<Long, Mutex>()
    private val clients = ConcurrentHashMap<Long, ImapClient>()

    suspend fun <T> use(accountId: Long, block: (ImapClient) -> T): T =
        mutexes.getOrPut(accountId) { Mutex() }.withLock {
            withContext(Dispatchers.IO) {
                try {
                    runWith(accountId, block)
                } catch (e: Throwable) {
                    dropClient(accountId)
                    // An access token that expired or was revoked looks exactly like
                    // a bad password; fetch a fresh one and give it one more go.
                    if (!shouldRetryWithNewToken(accountId, e)) throw e
                    tokenStore.invalidateAccessToken(accountId)
                    try {
                        runWith(accountId, block)
                    } catch (retry: Throwable) {
                        dropClient(accountId)
                        throw retry
                    }
                }
            }
        }

    private suspend fun <T> runWith(accountId: Long, block: (ImapClient) -> T): T {
        val client = clients[accountId]?.takeIf { it.isConnected } ?: newClient(accountId)
        clients[accountId] = client
        return block(client)
    }

    /**
     * A fresh, unpooled client — for IMAP IDLE, which parks the connection.
     * [forIdle] gives it a read timeout long enough to sit in IDLE.
     */
    suspend fun newClient(accountId: Long, forIdle: Boolean = false): ImapClient =
        withContext(Dispatchers.IO) {
            val account = accountDao.get(accountId)
                ?: throw MailException("Account $accountId no longer exists")
            ImapClient(
                account,
                tokenStore.imapSecret(account),
                if (forIdle) ImapClient.IDLE_READ_TIMEOUT_MILLIS
                else ImapClient.DEFAULT_READ_TIMEOUT_MILLIS
            ).also { it.connect() }
        }

    private suspend fun shouldRetryWithNewToken(accountId: Long, error: Throwable): Boolean {
        val account = accountDao.get(accountId) ?: return false
        if (tokenStore.authType(account) != AuthType.OAUTH2) return false
        val text = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
        return text.contains("AUTHENTICATIONFAILED", true) ||
            text.contains("Invalid credentials", true) ||
            text.contains("authentication fail", true) ||
            text.contains("access token", true)
    }

    private fun dropClient(accountId: Long) {
        clients.remove(accountId)?.let { runCatching { it.close() } }
    }

    fun evict(accountId: Long) = dropClient(accountId)

    fun evictAll() {
        clients.keys.toList().forEach { evict(it) }
    }
}
