package de.uwumail.mail.oauth

import de.uwumail.data.crypto.CredentialStore
import de.uwumail.data.db.AccountDao
import de.uwumail.data.db.AccountEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the secret the mail servers actually want.
 *
 * For a password account that is the stored password; for an OAuth account it is
 * a currently-valid access token, refreshed on demand. Refreshes are serialised
 * per account so a burst of folder syncs cannot spend the refresh token several
 * times over.
 */
class TokenStore(
    private val accountDao: AccountDao,
    private val credentials: CredentialStore,
    private val client: OAuthClient,
    private val config: OAuthConfig
) {

    private val mutexes = ConcurrentHashMap<Long, Mutex>()

    suspend fun imapSecret(account: AccountEntity): String = when (authType(account)) {
        AuthType.OAUTH2 -> accessToken(account)
        AuthType.PASSWORD -> credentials.get(credentials.imapKey(account.id))
            ?: throw OAuthException("No stored IMAP password for ${account.email}")
    }

    suspend fun smtpSecret(account: AccountEntity): String = when (authType(account)) {
        AuthType.OAUTH2 -> accessToken(account)
        AuthType.PASSWORD -> credentials.get(credentials.smtpKey(account.id))
            ?: throw OAuthException("No stored SMTP password for ${account.email}")
    }

    fun authType(account: AccountEntity): AuthType =
        runCatching { AuthType.valueOf(account.authType) }.getOrDefault(AuthType.PASSWORD)

    /** Persists what a sign-in or refresh returned. */
    fun store(accountId: Long, tokens: TokenSet) {
        tokens.refreshToken?.let { credentials.put(credentials.refreshTokenKey(accountId), it) }
        credentials.put(
            credentials.accessTokenKey(accountId),
            JSONObject()
                .put("access_token", tokens.accessToken)
                .put("expires_at", tokens.expiresAtMillis)
                .toString()
        )
    }

    /** Forces the next call to fetch a new access token; used after an auth failure. */
    fun invalidateAccessToken(accountId: Long) {
        credentials.remove(credentials.accessTokenKey(accountId))
    }

    fun hasRefreshToken(accountId: Long): Boolean =
        credentials.get(credentials.refreshTokenKey(accountId)) != null

    private suspend fun accessToken(account: AccountEntity): String =
        mutexes.getOrPut(account.id) { Mutex() }.withLock {
            cachedAccessToken(account.id)?.let { return@withLock it }

            val provider = OAuthProvider.byId(account.oauthProvider)
                ?: throw OAuthException("Unknown OAuth provider for ${account.email}")
            val clientId = config.clientId(provider)
                ?: throw OAuthException(
                    "No ${provider.label} OAuth client id configured. Add one in Settings."
                )
            val refreshToken = credentials.get(credentials.refreshTokenKey(account.id))
                ?: throw OAuthException(
                    "${account.email} is not signed in any more. Open the account and sign in again."
                )

            val tokens = client.refresh(provider, clientId, refreshToken)
            store(account.id, tokens)
            tokens.accessToken
        }

    private fun cachedAccessToken(accountId: Long): String? {
        val raw = credentials.get(credentials.accessTokenKey(accountId)) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val expiresAt = json.optLong("expires_at", 0)
            if (System.currentTimeMillis() >= expiresAt) null
            else json.optString("access_token").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** Clears everything for an account that is being removed or re-authenticated. */
    fun clear(accountId: Long) {
        credentials.remove(credentials.refreshTokenKey(accountId))
        credentials.remove(credentials.accessTokenKey(accountId))
        mutexes.remove(accountId)
    }
}
