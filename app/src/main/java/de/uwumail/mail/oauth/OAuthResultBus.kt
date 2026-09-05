package de.uwumail.mail.oauth

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hands the browser's redirect from the activity to whichever screen started it.
 *
 * The value is held rather than broadcast, because the setup screen may not be
 * composed yet at the moment the redirect arrives.
 */
class OAuthResultBus {

    data class Result(val code: String?, val state: String?, val error: String?)

    private val _latest = MutableStateFlow<Result?>(null)
    val latest = _latest.asStateFlow()

    fun post(uri: Uri) {
        _latest.value = Result(
            code = uri.getQueryParameter("code"),
            state = uri.getQueryParameter("state"),
            error = uri.getQueryParameter("error")
        )
    }

    /** Called once the result has been acted on, so it is not replayed. */
    fun consume() {
        _latest.value = null
    }

    fun isRedirect(uri: Uri, redirectUri: String): Boolean =
        redirectUri.startsWith("${uri.scheme}:")
}
