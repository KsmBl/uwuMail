package de.uwumail.mail.oauth

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class Pkce(val verifier: String, val challenge: String)

/**
 * Authorization Code flow with PKCE, which is what a native app must use:
 * there is no client secret to keep, and the code is bound to a verifier only
 * this process knows.
 */
class OAuthClient {

    fun createPkce(): Pkce {
        val bytes = ByteArray(64).also { SecureRandom().nextBytes(it) }
        val verifier = base64Url(bytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Pkce(verifier, base64Url(digest))
    }

    fun randomState(): String =
        base64Url(ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun buildAuthorizationUri(
        provider: OAuthProvider,
        clientId: String,
        redirectUri: String,
        pkce: Pkce,
        state: String,
        loginHint: String? = null
    ): Uri = Uri.parse(provider.authorizationEndpoint).buildUpon().apply {
        appendQueryParameter("client_id", clientId)
        appendQueryParameter("redirect_uri", redirectUri)
        appendQueryParameter("response_type", "code")
        appendQueryParameter("scope", provider.scopeString)
        appendQueryParameter("code_challenge", pkce.challenge)
        appendQueryParameter("code_challenge_method", "S256")
        appendQueryParameter("state", state)
        loginHint?.takeIf { it.isNotBlank() }?.let { appendQueryParameter("login_hint", it) }
        provider.extraAuthParams.forEach { (key, value) -> appendQueryParameter(key, value) }
    }.build()

    suspend fun exchangeCode(
        provider: OAuthProvider,
        clientId: String,
        redirectUri: String,
        code: String,
        verifier: String
    ): TokenSet = post(
        provider.tokenEndpoint,
        mapOf(
            "client_id" to clientId,
            "code" to code,
            "code_verifier" to verifier,
            "grant_type" to "authorization_code",
            "redirect_uri" to redirectUri
        )
    ).let(::parseTokens)

    suspend fun refresh(
        provider: OAuthProvider,
        clientId: String,
        refreshToken: String
    ): TokenSet = post(
        provider.tokenEndpoint,
        mapOf(
            "client_id" to clientId,
            "refresh_token" to refreshToken,
            "grant_type" to "refresh_token"
        )
    ).let { json ->
        // A refresh response usually omits refresh_token; the old one stays valid.
        parseTokens(json).let { it.copy(refreshToken = it.refreshToken ?: refreshToken) }
    }

    /**
     * Resolves which address was actually authorised. Prefers the id_token so we
     * do not spend a round trip, falling back to the userinfo endpoint.
     */
    suspend fun resolveEmail(provider: OAuthProvider, tokens: TokenSet): String? {
        emailFromIdToken(tokens.idToken)?.let { return it }
        val endpoint = provider.userInfoEndpoint ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Authorization", "Bearer ${tokens.accessToken}")
                }
                try {
                    if (connection.responseCode != 200) return@runCatching null
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    JSONObject(body).optString("email").takeIf { it.isNotBlank() }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        }
    }

    /** Reads the address out of the id_token's payload without verifying it. */
    internal fun emailFromIdToken(idToken: String?): String? {
        val payload = idToken?.split('.')?.getOrNull(1) ?: return null
        return runCatching {
            val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
            JSONObject(json).optString("email").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private suspend fun post(endpoint: String, form: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val body = form.entries.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            }
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20000
                readTimeout = 20000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
            }
            try {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val text = if (code in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw OAuthException(describe(code, error))
                }
                JSONObject(text)
            } catch (e: OAuthException) {
                throw e
            } catch (e: Exception) {
                throw OAuthException("Token request failed: ${e.message}", e)
            } finally {
                connection.disconnect()
            }
        }

    private fun describe(code: Int, body: String): String {
        val parsed = runCatching { JSONObject(body) }.getOrNull()
        val error = parsed?.optString("error").orEmpty()
        val description = parsed?.optString("error_description").orEmpty()
        return when {
            error == "invalid_grant" ->
                "Sign-in expired or was revoked. Sign in again."
            error == "invalid_client" ->
                "Google rejected the OAuth client id. Check it in Settings, and that the " +
                    "package name and SHA-1 fingerprint match the Android client."
            error == "redirect_uri_mismatch" ->
                "Google rejected the redirect URI. The OAuth client must be of type Android " +
                    "with package de.uwumail."
            description.isNotBlank() -> "$error: $description"
            error.isNotBlank() -> error
            else -> "Token request failed (HTTP $code)"
        }
    }

    internal fun parseTokens(json: JSONObject): TokenSet {
        val accessToken = json.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw OAuthException("Provider returned no access token")
        val expiresIn = json.optLong("expires_in", 3600L)
        return TokenSet(
            accessToken = accessToken,
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
            // Refresh a minute early so a request never races the expiry.
            expiresAtMillis = System.currentTimeMillis() + (expiresIn - 60).coerceAtLeast(0) * 1000,
            idToken = json.optString("id_token").takeIf { it.isNotBlank() }
        )
    }

    // java.util.Base64 rather than android.util.Base64: identical output, and it
    // lets the PKCE maths be covered by plain JVM tests.
    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
