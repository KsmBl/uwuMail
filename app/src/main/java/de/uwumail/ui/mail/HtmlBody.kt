package de.uwumail.ui.mail

import android.graphics.Bitmap
import android.graphics.Canvas
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
import de.uwumail.ui.mail.gravity.FallingPiece
import de.uwumail.ui.mail.gravity.GlyphReader
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
    onGlyphs: (List<FallingPiece>) -> Unit = {}
) {
    val currentOnLink by rememberUpdatedState(onLink)
    val currentPolicy by rememberUpdatedState(imagePolicy)
    val currentImages by rememberUpdatedState(allowRemoteImages)
    val currentOnGlyphs by rememberUpdatedState(onGlyphs)

    var view by remember { mutableStateOf<WebView?>(null) }
    var loadedPages by remember { mutableStateOf(0) }
    // Whether this page is currently drawing its text in nothing.
    var textHidden by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(Offset.Zero) }
    // What the view currently shows, so a recomposition does not reload the
    // body and throw the reader's scroll position away.
    val loaded = remember { mutableStateOf<Triple<String, Boolean, Boolean>?>(null) }

    LaunchedEffect(view, loadedPages, handOverGlyphs, position) {
        val web = view ?: return@LaunchedEffect
        if (loadedPages == 0) return@LaunchedEffect
        if (!handOverGlyphs) {
            if (textHidden) {
                // evaluateJavascript does nothing at all while scripting is
                // off, so putting the text back has to switch it on for the
                // one call, exactly as taking the text away did.
                web.settings.javaScriptEnabled = true
                web.evaluate(PageGlyphs.showText)
                web.settings.javaScriptEnabled = allowJavaScript
                textHidden = false
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
            GlyphReader.parseGlyphs(array, ratio, position.x, position.y, glyphLimit)
        }.orEmpty()

        // The pictures are lifted as pixels, taken off the page as it stands.
        // There is no other way to get at them: what is on screen is the only
        // place the rendered, scaled image exists.
        val pictures = payload?.optJSONArray("images")?.let { array ->
            val rects = GlyphReader.parseImageRects(array, ratio)
            if (rects.isEmpty()) emptyList() else cutOut(web, visible, rects, position)
        }.orEmpty()

        if (glyphs.isNotEmpty() || pictures.isNotEmpty()) {
            web.evaluate(PageGlyphs.hideText)
            textHidden = true
        }
        web.settings.javaScriptEnabled = allowJavaScript
        currentOnGlyphs(glyphs + pictures)
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
            // A fresh document draws its own text again.
            textHidden = false
        }
    )
}

/**
 * Copies each picture's pixels out of the rendered page.
 *
 * The view is drawn once into a bitmap of the visible area and the rectangles
 * are cut from that, so a picture falls looking exactly as it did — at the size
 * the page gave it, with whatever scaling the page applied.
 */
private fun cutOut(
    web: WebView,
    visible: Rect,
    rects: List<FloatArray>,
    position: Offset
): List<FallingPiece> {
    if (visible.width() <= 0 || visible.height() <= 0) return emptyList()
    val page = runCatching {
        Bitmap.createBitmap(visible.width(), visible.height(), Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            canvas.translate(-visible.left.toFloat(), -visible.top.toFloat())
            web.draw(canvas)
        }
    }.getOrNull() ?: return emptyList()

    val pieces = rects.mapNotNull { rect ->
        val left = (rect[0] - visible.left).toInt()
        val top = (rect[1] - visible.top).toInt()
        val width = rect[2].toInt()
        val height = rect[3].toInt()
        // Anything not wholly on screen is left in the page rather than falling
        // as a half picture.
        if (left < 0 || top < 0) return@mapNotNull null
        if (left + width > page.width || top + height > page.height) return@mapNotNull null
        if (width <= 0 || height <= 0) return@mapNotNull null
        val cut = runCatching { Bitmap.createBitmap(page, left, top, width, height) }
            .getOrNull() ?: return@mapNotNull null
        FallingPiece(
            x = position.x + rect[0],
            y = position.y + rect[1],
            width = rect[2],
            height = rect[3],
            bitmap = cut
        )
    }
    page.recycle()
    return pieces
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
