package de.uwumail.mail

import org.jsoup.Jsoup

/**
 * Where a message says it can be unsubscribed from.
 *
 * [oneClick] marks a List-Unsubscribe=One-Click sender (RFC 8058): those accept
 * an unauthenticated POST, but a plain GET in the browser works too and is what
 * uwuMail does, so the flag is only used to word the banner.
 */
data class UnsubscribeTarget(
    val url: String,
    val isMailto: Boolean,
    val oneClick: Boolean = false,
    /** True when the link came from the body rather than a header. */
    val fromBody: Boolean = false
)

/**
 * Finds the unsubscribe link a message advertises.
 *
 * `List-Unsubscribe` is the authoritative source and is preferred; its HTTPS
 * entry wins over its mailto entry, since opening a URL is one tap and sending
 * a mail is not. Bulk senders that skip the header almost always still put a
 * link in the body, so that is scanned as a fallback.
 */
object Unsubscribe {

    private val ANGLE = Regex("""<\s*([^>]+?)\s*>""")

    /** Words that mark an anchor as the unsubscribe link, in the languages we see. */
    private val BODY_HINTS = listOf(
        "unsubscribe", "un-subscribe", "opt out", "opt-out", "optout",
        "abmelden", "abbestellen", "abmeldung", "austragen", "newsletter abbestellen",
        "se désabonner", "desabonner", "désinscription", "darse de baja",
        "cancelar subscrição", "disiscriviti", "afmelden", "avsluta prenumeration"
    )

    fun find(
        headers: Map<String, List<String>>,
        html: String?,
        plain: String? = null
    ): UnsubscribeTarget? = fromHeaders(headers) ?: fromBody(html, plain)

    fun fromHeaders(headers: Map<String, List<String>>): UnsubscribeTarget? {
        val raw = headers.entries
            .firstOrNull { it.key.equals("list-unsubscribe", ignoreCase = true) }
            ?.value?.firstOrNull()
            ?: return null

        val oneClick = headers.entries
            .firstOrNull { it.key.equals("list-unsubscribe-post", ignoreCase = true) }
            ?.value.orEmpty()
            .any { it.contains("one-click", ignoreCase = true) }

        // The header is a comma-separated list of <URI>; anything outside the
        // angle brackets is a comment and must not be followed.
        val uris = ANGLE.findAll(raw).map { it.groupValues[1].trim() }.toList()
            .ifEmpty { raw.split(',').map { it.trim() }.filter { it.isNotBlank() } }

        val http = uris.firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
        if (http != null) return UnsubscribeTarget(http, isMailto = false, oneClick = oneClick)

        val mailto = uris.firstOrNull { it.startsWith("mailto:", true) }
        return mailto?.let { UnsubscribeTarget(it, isMailto = true, oneClick = oneClick) }
    }

    /** Scans a rendered body for a link that names itself as an unsubscribe. */
    fun fromBody(html: String?, plain: String?): UnsubscribeTarget? {
        if (!html.isNullOrBlank()) {
            val document = runCatching { Jsoup.parse(html) }.getOrNull()
            document?.select("a[href]")?.forEach { anchor ->
                val href = anchor.attr("href").trim()
                if (!isFollowable(href)) return@forEach
                val haystack = (anchor.text() + " " + anchor.attr("title") + " " + href).lowercase()
                if (BODY_HINTS.any { haystack.contains(it) }) {
                    return UnsubscribeTarget(
                        url = href,
                        isMailto = href.startsWith("mailto:", true),
                        fromBody = true
                    )
                }
            }
        }
        // A plain-text newsletter puts the URL on its own line near the word.
        if (!plain.isNullOrBlank()) {
            val lower = plain.lowercase()
            if (BODY_HINTS.none { lower.contains(it) }) return null
            val url = Regex("""https?://\S{6,}""").findAll(plain)
                .map { it.value.trimEnd('.', ',', ')', '>', ']') }
                .firstOrNull { candidate ->
                    val at = lower.indexOf(candidate.lowercase())
                    at >= 0 && BODY_HINTS.any { hint ->
                        val hintAt = lower.lastIndexOf(hint, at)
                        hintAt >= 0 && at - hintAt < 200
                    }
                }
            if (url != null) return UnsubscribeTarget(url, isMailto = false, fromBody = true)
        }
        return null
    }

    private fun isFollowable(href: String) =
        href.startsWith("http://", true) || href.startsWith("https://", true) ||
            href.startsWith("mailto:", true)
}
