package de.uwumail.mail

import de.uwumail.data.crypto.CredentialStore
import de.uwumail.data.db.AccountDao
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
    private val credentials: CredentialStore
) {
    private val mutexes = ConcurrentHashMap<Long, Mutex>()
    private val clients = ConcurrentHashMap<Long, ImapClient>()

    suspend fun <T> use(accountId: Long, block: (ImapClient) -> T): T =
        mutexes.getOrPut(accountId) { Mutex() }.withLock {
            withContext(Dispatchers.IO) {
                val client = clients[accountId]?.takeIf { it.isConnected } ?: newClient(accountId)
                clients[accountId] = client
                try {
                    block(client)
                } catch (e: Throwable) {
                    clients.remove(accountId)?.let { runCatching { it.close() } }
                    throw e
                }
            }
        }

    /** A fresh, unpooled client — for IMAP IDLE, which parks the connection. */
    suspend fun newClient(accountId: Long): ImapClient = withContext(Dispatchers.IO) {
        val account = accountDao.get(accountId)
            ?: throw MailException("Account $accountId no longer exists")
        val password = credentials.get(credentials.imapKey(accountId))
            ?: throw MailException("No stored IMAP password for ${account.email}")
        ImapClient(account, password).also { it.connect() }
    }

    fun evict(accountId: Long) {
        clients.remove(accountId)?.let { runCatching { it.close() } }
    }

    fun evictAll() {
        clients.keys.toList().forEach { evict(it) }
    }
}
