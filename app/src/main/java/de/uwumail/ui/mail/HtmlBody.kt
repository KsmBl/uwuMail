package de.uwumail.ui.mail

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import de.uwumail.mail.RemoteImagePolicy

/**
 * Renders an HTML mail body.
 *
 * Mail is untrusted content, so the defaults are the closed ones: no scripts,
 * no network, no file system, and every link the user taps leaves for the
 * system browser rather than navigating inside the view — a body that could
 * navigate itself could put up a page that looks like the app.
 *
 * [allowRemoteImages] is what the "show images" banner turns on, and applies to
 * this one viewing of this one message.
 */
@Composable
fun HtmlBody(
    html: String,
    allowRemoteImages: Boolean,
    allowJavaScript: Boolean,
    imagePolicy: RemoteImagePolicy,
    onLink: (String) -> Unit
) {
    val currentOnLink by rememberUpdatedState(onLink)
    val currentPolicy by rememberUpdatedState(imagePolicy)
    val currentImages by rememberUpdatedState(allowRemoteImages)
    // What the view currently shows, so a recomposition does not reload the
    // body and throw the reader's scroll position away.
    val loaded = remember { mutableStateOf<Triple<String, Boolean, Boolean>?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            WebView(context).apply {
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        request?.url?.toString()?.let(currentOnLink)
                        return true
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        if (!currentImages) return null
                        val url = request?.url?.toString() ?: return null
                        return RemoteImageInterceptor.intercept(url, currentPolicy)
                    }
                }
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                isVerticalScrollBarEnabled = false
                setBackgroundColor(0)
            }
        },
        update = { view ->
            val stamp = Triple(html, allowRemoteImages, allowJavaScript)
            if (loaded.value == stamp) return@AndroidView
            view.settings.javaScriptEnabled = allowJavaScript
            view.settings.blockNetworkLoads = !allowRemoteImages
            view.settings.loadsImagesAutomatically = allowRemoteImages
            view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            loaded.value = stamp
        }
    )
}
