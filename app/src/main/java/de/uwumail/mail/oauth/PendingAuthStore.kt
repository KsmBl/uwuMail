package de.uwumail.mail.oauth

import android.content.Context

/**
 * The PKCE verifier and state for an in-flight sign-in.
 *
 * This survives on disk because the browser hop can take the app process with
 * it; without persisting, the redirect would come back to a process that no
 * longer knows what it asked for.
 */
class PendingAuthStore(context: Context) {

    private val prefs = context.getSharedPreferences("oauth_pending", Context.MODE_PRIVATE)

    data class Pending(
        val providerId: String,
        val verifier: String,
        val state: String,
        val accountId: Long,
        val startedAt: Long
    )

    fun put(providerId: String, pkce: Pkce, state: String, accountId: Long) {
        prefs.edit()
            .putString(KEY_PROVIDER, providerId)
            .putString(KEY_VERIFIER, pkce.verifier)
            .putString(KEY_STATE, state)
            .putLong(KEY_ACCOUNT, accountId)
            .putLong(KEY_STARTED, System.currentTimeMillis())
            .apply()
    }

    fun take(): Pending? {
        val provider = prefs.getString(KEY_PROVIDER, null) ?: return null
        val verifier = prefs.getString(KEY_VERIFIER, null) ?: return null
        val state = prefs.getString(KEY_STATE, null) ?: return null
        val startedAt = prefs.getLong(KEY_STARTED, 0)
        clear()
        // An authorization that has been sitting around for an hour is not one
        // the user is still waiting on.
        if (System.currentTimeMillis() - startedAt > MAX_AGE_MILLIS) return null
        return Pending(provider, verifier, state, prefs.getLong(KEY_ACCOUNT, 0), startedAt)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_VERIFIER = "verifier"
        const val KEY_STATE = "state"
        const val KEY_ACCOUNT = "account"
        const val KEY_STARTED = "started"
        const val MAX_AGE_MILLIS = 60 * 60 * 1000L
    }
}
