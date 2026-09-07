package de.uwumail.mail

/**
 * Works out which conversation a message belongs to.
 *
 * A mail says where it sits in a thread with two headers: `In-Reply-To` names
 * the message it answers, and `References` lists the whole chain back to the
 * one that started it. The first entry of `References` is therefore the root,
 * and every message in a conversation agrees on it — which makes it the one
 * value the whole thread can be gathered by, without having to walk a tree or
 * guess from subject lines.
 *
 * Guessing from subjects is deliberately not done. "Re: lunch?" from two people
 * in the same week is two conversations, and merging them puts one person's
 * mail inside another's.
 */
object Threading {

    /**
     * The id every message in this conversation shares.
     *
     * Falls back through what is available: the root named by `References`,
     * then the message being answered, then the message's own id — a mail that
     * started a thread is the root of it. A message with no usable id at all
     * gets [fallback], which keeps it in a conversation of its own rather than
     * lumping every unidentifiable message together.
     */
    fun threadIdOf(
        messageId: String?,
        inReplyTo: String?,
        references: String?,
        fallback: String
    ): String =
        firstId(references)
            ?: firstId(inReplyTo)
            ?: messageId?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallback

    /** The same, read straight off a decoded header map. */
    fun threadIdOf(
        messageId: String?,
        headers: Map<String, List<String>>,
        fallback: String
    ): String = threadIdOf(
        messageId = messageId,
        inReplyTo = headers["in-reply-to"]?.firstOrNull(),
        references = headers["references"]?.firstOrNull(),
        fallback = fallback
    )

    /**
     * The first `<...>` in a header that may hold several, whitespace- or
     * comma-separated. Anything that is not bracketed is not a message id:
     * servers put all sorts in these headers and a fragment of prose gathered
     * as a thread root would pull unrelated mail together.
     */
    private fun firstId(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val open = text.indexOf('<')
        if (open < 0) return null
        val close = text.indexOf('>', open + 1)
        if (close < 0) return null
        return text.substring(open, close + 1).takeIf { it.length > 2 }
    }
}
