package de.uwumail.mail

/**
 * What a mail body is allowed to fetch once the user has asked for its images.
 *
 * Kept free of Android types so the decisions can be tested directly; the
 * fetching itself lives in the WebView's resource interceptor.
 */
data class RemoteImagePolicy(
    val filterTiny: Boolean,
    val minWidth: Int,
    val minHeight: Int
) {

    /**
     * True for an image small enough to exist only to report that the mail was
     * opened. Either dimension being under the limit is enough: a 1x600 spacer
     * tracks just as well as a 1x1 pixel.
     *
     * Unknown dimensions (a format the decoder does not understand, such as
     * SVG) are never treated as tiny — refusing to show something we could not
     * measure would break real mail.
     */
    fun isTrackingPixel(width: Int, height: Int): Boolean {
        if (!filterTiny) return false
        if (width <= 0 || height <= 0) return false
        return width < minWidth || height < minHeight
    }

    /**
     * Only images are worth fetching for a mail body. Remote stylesheets and
     * fonts report an open just as reliably and are not needed to read mail,
     * so anything else is refused.
     */
    fun allowsContentType(contentType: String?): Boolean =
        contentType != null && contentType.substringBefore(';').trim()
            .startsWith("image/", ignoreCase = true)

    companion object {
        /** Beyond this an image is certainly not a tracking pixel, so it is not buffered. */
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}
