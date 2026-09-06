package de.uwumail.mail

/**
 * Matches a `cid:` reference in a body against a part's `Content-ID` header.
 *
 * The two are written differently on either side: the header carries angle
 * brackets, the reference in the HTML does not, case is not significant, and
 * the reference may have been percent-encoded on its way through the markup.
 * Getting any of that wrong shows a broken image rather than the picture the
 * sender put there.
 */
object ContentId {

    /** The comparable form of either side of the match. */
    fun normalise(value: String?): String {
        if (value == null) return ""
        val decoded = runCatching { java.net.URLDecoder.decode(value.trim(), "UTF-8") }
            .getOrDefault(value.trim())
        return decoded.trim().removePrefix("<").removeSuffix(">").trim().lowercase()
    }

    /** Whether a `cid:` reference names the part carrying [header]. */
    fun matches(reference: String?, header: String?): Boolean {
        val left = normalise(reference)
        return left.isNotEmpty() && left == normalise(header)
    }
}
