package de.uwumail.mail.oauth

import de.uwumail.core.Security

/** How an account proves who it is to the mail servers. */
enum class AuthType { PASSWORD, OAUTH2 }

/**
 * An OAuth2 identity provider that also hosts mail.
 *
 * The mail server coordinates live here too: once you have signed in there is
 * nothing left for the user to type, so the setup screen fills itself in.
 */
data class OAuthProvider(
    val id: String,
    val label: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val userInfoEndpoint: String?,
    val scopes: List<String>,
    val imapHost: String,
    val imapPort: Int,
    val imapSecurity: Security,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSecurity: Security,
    /** Provider-specific query parameters on the authorization request. */
    val extraAuthParams: Map<String, String> = emptyMap()
) {
    val scopeString: String get() = scopes.joinToString(" ")

    companion object {
        val GOOGLE = OAuthProvider(
            id = "GOOGLE",
            label = "Google",
            authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
            tokenEndpoint = "https://oauth2.googleapis.com/token",
            userInfoEndpoint = "https://openidconnect.googleapis.com/v1/userinfo",
            // https://mail.google.com/ is the full IMAP/SMTP scope; the other two
            // are only so we can read back which address was picked.
            scopes = listOf("https://mail.google.com/", "openid", "email"),
            imapHost = "imap.gmail.com",
            imapPort = 993,
            imapSecurity = Security.SSL_TLS,
            smtpHost = "smtp.gmail.com",
            smtpPort = 587,
            smtpSecurity = Security.STARTTLS,
            extraAuthParams = mapOf(
                // Without these Google only returns a refresh token on the very
                // first consent, and we would silently lose access later.
                "access_type" to "offline",
                "prompt" to "consent"
            )
        )

        val ALL = listOf(GOOGLE)

        fun byId(id: String?): OAuthProvider? = ALL.firstOrNull { it.id == id }

        /** Suggests a provider from the address domain, or null for a plain server. */
        fun forEmail(email: String): OAuthProvider? =
            when (email.substringAfterLast('@').lowercase()) {
                "gmail.com", "googlemail.com" -> GOOGLE
                else -> null
            }
    }
}

data class TokenSet(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMillis: Long,
    val idToken: String? = null
) {
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis >= expiresAtMillis
}

class OAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
