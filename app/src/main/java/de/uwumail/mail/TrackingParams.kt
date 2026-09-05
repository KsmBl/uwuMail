package de.uwumail.mail

import java.net.URI

/**
 * Strips the query parameters that exist only to identify the person who
 * clicked a link.
 *
 * The list is conservative on purpose: a parameter is only removed when it
 * carries no meaning for the destination page, because dropping a functional
 * parameter breaks the link and that is far worse than leaking a click. Path
 * segments are never touched — plenty of senders encode the target inside the
 * path, and there is no way to tell those apart from tracking ids.
 */
object TrackingParams {

    /** Exact parameter names, matched case-insensitively. */
    private val EXACT = setOf(
        // Google Analytics and the rest of the utm_ family are matched by prefix.
        "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "gad_source", "gads",
        // Facebook / Meta
        "fbclid", "fb_action_ids", "fb_action_types", "fb_source", "fb_ref",
        // Microsoft, Twitter/X, TikTok, LinkedIn, Yandex, Mailchimp, Klaviyo
        "msclkid", "twclid", "ttclid", "li_fat_id", "yclid", "ysclid",
        "mc_cid", "mc_eid", "_kx",
        // HubSpot, Marketo, Pardot, Braze, Iterable, Salesforce
        "_hsenc", "_hsmi", "hsctatracking", "hsa_acc", "hsa_cam", "hsa_grp",
        "hsa_ad", "hsa_src", "hsa_tgt", "hsa_kw", "hsa_mt", "hsa_net", "hsa_ver",
        "mkt_tok", "trk", "trkcampaign", "sc_campaign", "sc_channel", "sc_content",
        "sc_medium", "sc_outcome", "sc_geo", "sc_country",
        // Generic click/open beacons used by mail service providers
        "ck_subscriber_id", "vero_conv", "vero_id", "wickedid", "oly_anon_id",
        "oly_enc_id", "rb_clickid", "s_cid", "elqtrackid", "elqtrack",
        "spm", "scm", "igshid", "srsltid", "ref_src", "ref_url",
        "piwik_campaign", "piwik_kwd", "pk_campaign", "pk_kwd", "pk_source",
        "pk_medium", "pk_content", "matomo_campaign", "matomo_kwd",
        "email_source", "email_campaign", "campaignid", "customerid",
        "recipientid", "recipient_id", "subscriberid", "subscriber_id"
    )

    /** Prefixes, for the families whose members are open-ended. */
    private val PREFIXES = listOf("utm_", "at_", "ns_", "cm_", "mtm_", "stm_", "hmb_")

    fun isTracking(name: String): Boolean {
        val key = name.lowercase()
        return key in EXACT || PREFIXES.any { key.startsWith(it) }
    }

    /** The tracking parameters present in [url], in the order they appear. */
    fun findIn(url: String): List<String> =
        queryOf(url).orEmpty()
            .let(::splitPairs)
            .map { it.first }
            .filter { isTracking(it) }

    fun hasTracking(url: String): Boolean = findIn(url).isNotEmpty()

    /**
     * [url] with its tracking parameters removed. Returns the input unchanged
     * when there is nothing to strip or the URL cannot be parsed, so a caller
     * can use the result unconditionally.
     */
    fun strip(url: String): String {
        val query = queryOf(url) ?: return url
        val pairs = splitPairs(query)
        val kept = pairs.filterNot { isTracking(it.first) }
        if (kept.size == pairs.size) return url

        val queryStart = url.indexOf('?')
        val fragmentStart = url.indexOf('#', queryStart + 1)
        val head = url.substring(0, queryStart)
        val tail = if (fragmentStart >= 0) url.substring(fragmentStart) else ""
        val rebuilt = kept.joinToString("&") { (name, value) ->
            if (value == null) name else "$name=$value"
        }
        return if (rebuilt.isEmpty()) head + tail else "$head?$rebuilt$tail"
    }

    /** The raw query string of [url], or null when it has none. */
    private fun queryOf(url: String): String? {
        // Parsed by hand rather than with URI: mail links are frequently not
        // strictly valid, and URI would throw and leave the link unopenable.
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return null
        val fragmentStart = url.indexOf('#', queryStart)
        val end = if (fragmentStart >= 0) fragmentStart else url.length
        return url.substring(queryStart + 1, end).takeIf { it.isNotEmpty() }
    }

    private fun splitPairs(query: String): List<Pair<String, String?>> =
        query.split('&').filter { it.isNotEmpty() }.map { pair ->
            val equals = pair.indexOf('=')
            if (equals < 0) pair to null else pair.substring(0, equals) to pair.substring(equals + 1)
        }

    /** The host of [url], for showing the user where a link actually goes. */
    fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.removePrefix("www.") ?: url
}
