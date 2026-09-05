package de.uwumail.ui.mail

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Renders an HTML mail body.
 *
 * Mail is untrusted content: scripts are off, the page cannot reach the file
 * system, and every link the user taps leaves for the system browser instead of
 * navigating inside the view — a body that could navigate itself could load a
 * page that looks like the app.
 */
@Composable
fun HtmlBody(
    html: String,
    onLink: (String) -> Unit
) {
    val currentOnLink by rememberUpdatedState(onLink)

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
                }
                settings.javaScriptEnabled = false
                settings.blockNetworkLoads = true
                settings.loadsImagesAutomatically = false
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
            view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
    )
}
