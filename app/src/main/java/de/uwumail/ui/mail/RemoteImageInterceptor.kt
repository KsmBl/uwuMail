package de.uwumail.ui.mail

import android.graphics.BitmapFactory
import android.webkit.WebResourceResponse
import de.uwumail.mail.RemoteImagePolicy
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a remote image on the WebView's behalf so its size can be checked
 * before it is handed over.
 *
 * uwuMail does the request itself rather than letting the WebView do it: the
 * decision needs the pixel dimensions, and those are only knowable once some
 * bytes have arrived. Doing the fetch here also keeps it out of the WebView's
 * cookie store and referrer handling, so nothing beyond the URL is sent.
 */
object RemoteImageInterceptor {

    /** An empty 200, which the WebView renders as a broken/absent image. */
    private fun blocked() =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /**
     * Returns the response to give the WebView, or null to let it load the
     * request itself. Runs on a WebView worker thread, never the main thread.
     */
    fun intercept(url: String, policy: RemoteImagePolicy): WebResourceResponse? {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null

        val connection = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "image/*")
                // No referrer: the point is that the sender learns nothing but
                // that some client asked for the URL.
                setRequestProperty("Referer", "")
            }
        }.getOrNull() ?: return blocked()

        return try {
            val contentType = connection.contentType
            if (!policy.allowsContentType(contentType)) return blocked()

            val declared = connection.contentLength
            if (declared > RemoteImagePolicy.MAX_BYTES) return null

            val bytes = connection.inputStream.use { it.readAtMost(RemoteImagePolicy.MAX_BYTES) }
            if (bytes.isEmpty()) return blocked()

            if (policy.filterTiny) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (policy.isTrackingPixel(bounds.outWidth, bounds.outHeight)) return blocked()
            }

            WebResourceResponse(
                contentType.substringBefore(';').trim(),
                null,
                ByteArrayInputStream(bytes)
            )
        } catch (e: Throwable) {
            // A failed load must not leave the WebView waiting on us.
            blocked()
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /**
     * Reads up to [limit] bytes. Written out rather than using readNBytes,
     * which only exists from API 33 and this app runs from 31.
     */
    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream(DEFAULT_BUFFER_SIZE)
        val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
        while (buffer.size() < limit) {
            val read = read(chunk, 0, minOf(chunk.size, limit - buffer.size()))
            if (read <= 0) break
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private const val TIMEOUT_MILLIS = 15_000
}
