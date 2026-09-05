package de.uwumail.ui.mail

import android.graphics.Rect
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.viewinterop.AndroidView
import de.uwumail.mail.RemoteImagePolicy
import de.uwumail.ui.mail.gravity.GlyphReader
import de.uwumail.ui.mail.gravity.GravityGlyph
import de.uwumail.ui.mail.gravity.PageGlyphs
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

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
 *
 * When [handOverGlyphs] is set the page measures its own characters, reports
 * them through [onGlyphs] in root coordinates, and then makes its text
 * invisible while leaving everything else — images, backgrounds, rules —
 * untouched. Whoever asked for them draws them from then on.
 */
@Composable
fun HtmlBody(
    html: String,
    allowRemoteImages: Boolean,
    allowJavaScript: Boolean,
    imagePolicy: RemoteImagePolicy,
    onLink: (String) -> Unit,
    handOverGlyphs: Boolean = false,
    glyphLimit: Int = 0,
    onGlyphs: (List<GravityGlyph>) -> Unit = {}
) {
    val currentOnLink by rememberUpdatedState(onLink)
    val currentPolicy by rememberUpdatedState(imagePolicy)
    val currentImages by rememberUpdatedState(allowRemoteImages)
    val currentOnGlyphs by rememberUpdatedState(onGlyphs)

    var view by remember { mutableStateOf<WebView?>(null) }
    var loadedPages by remember { mutableStateOf(0) }
    var position by remember { mutableStateOf(Offset.Zero) }
    // What the view currently shows, so a recomposition does not reload the
    // body and throw the reader's scroll position away.
    val loaded = remember { mutableStateOf<Triple<String, Boolean, Boolean>?>(null) }

    LaunchedEffect(view, loadedPages, handOverGlyphs, position) {
        val web = view ?: return@LaunchedEffect
        if (loadedPages == 0) return@LaunchedEffect
        if (!handOverGlyphs) {
            if (web.settings.javaScriptEnabled || loaded.value != null) {
                web.evaluateJavascript(PageGlyphs.showText, null)
            }
            return@LaunchedEffect
        }
        // Let the layout settle before measuring where the characters are.
        delay(SETTLE_MILLIS)
        val visible = Rect().also { if (!web.getLocalVisibleRect(it)) it.set(0, 0, web.width, web.height) }

        // Scripting was off while the document was parsed, so the message's own
        // scripts never ran and cannot run now; only this measurement does.
        web.settings.javaScriptEnabled = true
        val report = web.evaluate(
            PageGlyphs.measure(glyphLimit, visible.top.toFloat(), visible.bottom.toFloat())
        )
        val payload = runCatching { JSONObject(unquote(report)) }.getOrNull()
        val ratio = payload?.optDouble("dpr", 1.0)?.toFloat() ?: 1f
        val glyphs = payload?.optJSONArray("glyphs")?.let { array ->
            GlyphReader.parse(array.toString(), ratio, position.x, position.y, glyphLimit)
        }.orEmpty()

        if (glyphs.isNotEmpty()) web.evaluate(PageGlyphs.hideText)
        web.settings.javaScriptEnabled = allowJavaScript
        currentOnGlyphs(glyphs)
    }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { position = it.positionInRoot() },
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

                    override fun onPageFinished(view: WebView?, url: String?) {
                        loadedPages++
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
                view = this
            }
        },
        update = { web ->
            val stamp = Triple(html, allowRemoteImages, allowJavaScript)
            if (loaded.value == stamp) return@AndroidView
            web.settings.javaScriptEnabled = allowJavaScript
            web.settings.blockNetworkLoads = !allowRemoteImages
            web.settings.loadsImagesAutomatically = allowRemoteImages
            web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            loaded.value = stamp
        }
    )
}

private suspend fun WebView.evaluate(script: String): String =
    suspendCancellableCoroutine { continuation ->
        evaluateJavascript(script) { continuation.resume(it.orEmpty()) }
    }

/** evaluateJavascript hands back a JSON *value*, so a string arrives quoted. */
private fun unquote(value: String): String {
    val trimmed = value.trim()
    if (!trimmed.startsWith("\"")) return trimmed
    return runCatching { org.json.JSONTokener(trimmed).nextValue() as? String }
        .getOrNull() ?: trimmed
}

/** Long enough for images to have laid out, short enough not to be a pause. */
private const val SETTLE_MILLIS = 120L
