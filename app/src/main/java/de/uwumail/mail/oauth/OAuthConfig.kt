package de.uwumail.mail.oauth

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import de.uwumail.BuildConfig
import java.security.MessageDigest

/**
 * Where the OAuth client id comes from, and the values needed to register one.
 *
 * The id is baked in from `local.properties` at build time when present, and can
 * always be overridden in the settings screen so a user with their own Google
 * Cloud project does not have to rebuild the app.
 */
class OAuthConfig(private val context: Context) {

    private val prefs = context.getSharedPreferences("oauth_config", Context.MODE_PRIVATE)

    val redirectUri: String get() = "${context.packageName}:/oauth2redirect"
    val packageName: String get() = context.packageName

    fun clientId(provider: OAuthProvider): String? {
        prefs.getString(key(provider), null)?.takeIf { it.isNotBlank() }?.let { return it }
        return when (provider.id) {
            OAuthProvider.GOOGLE.id -> BuildConfig.GOOGLE_OAUTH_CLIENT_ID.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    fun setClientId(provider: OAuthProvider, value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove(key(provider)) else putString(key(provider), value.trim())
        }.apply()
    }

    fun isConfigured(provider: OAuthProvider): Boolean = clientId(provider) != null

    /**
     * SHA-1 of the certificate this build is signed with — Google's Android
     * OAuth client is bound to it, so the setup screen shows it for copying.
     */
    fun signingFingerprintSha1(): String? = runCatching {
        val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = context.packageManager.getPackageInfo(
                context.packageName, PackageManager.GET_SIGNING_CERTIFICATES
            )
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName, PackageManager.GET_SIGNATURES
            ).signatures
        }
        val signature = certificates?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-1")
            .digest(signature.toByteArray())
            .joinToString(":") { "%02X".format(it) }
    }.getOrNull()

    private fun key(provider: OAuthProvider) = "client_id_${provider.id}"
}
