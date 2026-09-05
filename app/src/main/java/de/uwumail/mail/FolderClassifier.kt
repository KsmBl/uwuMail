package de.uwumail.mail

import de.uwumail.core.FolderType

/**
 * Works out what role a folder plays, and where it belongs in a folder list.
 *
 * Kept free of JavaMail types so the rules here can be exercised directly.
 */
object FolderClassifier {

    private val ARCHIVE_NAMES = setOf("archive", "archiv", "all mail", "alle nachrichten", "archives")
    private val SENT_NAMES = setOf(
        "sent", "sent items", "sent mail", "gesendet", "gesendete objekte", "gesendete elemente"
    )
    private val DRAFT_NAMES = setOf("drafts", "draft", "entwürfe", "entwuerfe")
    private val TRASH_NAMES = setOf(
        "trash", "deleted", "deleted items", "papierkorb", "gelöschte objekte", "geloeschte objekte"
    )
    private val SPAM_NAMES = setOf("junk", "spam", "junk e-mail", "bulk mail")

    /**
     * RFC 3501 makes INBOX case-insensitive, so `inbox`, `Inbox` and `INBOX` are
     * the same mailbox — but only at the top level: a nested `Foo/Inbox` is an
     * ordinary folder and must not be treated as the account's inbox.
     */
    fun isInbox(path: String): Boolean = path.equals("INBOX", ignoreCase = true)

    fun guess(path: String, delimiter: Char = '/', attributes: List<String> = emptyList()): String {
        if (isInbox(path)) return FolderType.INBOX.name

        // RFC 6154 SPECIAL-USE attributes beat any name guessing.
        attributes.forEach { attribute ->
            when (attribute.lowercase()) {
                "\\archive" -> return FolderType.ARCHIVE.name
                "\\sent" -> return FolderType.SENT.name
                "\\drafts" -> return FolderType.DRAFTS.name
                "\\trash" -> return FolderType.TRASH.name
                "\\junk" -> return FolderType.SPAM.name
                "\\all" -> return FolderType.ARCHIVE.name
            }
        }

        // Servers using a "." hierarchy name their folders INBOX.Sent and so on;
        // splitting on the server's own delimiter keeps dotted folder names intact.
        val leaf = path.substringAfterLast(delimiter).lowercase().trim()
        return when (leaf) {
            in ARCHIVE_NAMES -> FolderType.ARCHIVE.name
            in SENT_NAMES -> FolderType.SENT.name
            in DRAFT_NAMES -> FolderType.DRAFTS.name
            in TRASH_NAMES -> FolderType.TRASH.name
            in SPAM_NAMES -> FolderType.SPAM.name
            else -> FolderType.CUSTOM.name
        }
    }

    /** Lower sorts first. The inbox always leads, device folders always trail. */
    fun sortRank(type: String): Int = when (type) {
        FolderType.INBOX.name -> 0
        FolderType.DRAFTS.name -> 1
        FolderType.SENT.name -> 2
        FolderType.ARCHIVE.name -> 3
        FolderType.SPAM.name -> 4
        FolderType.TRASH.name -> 5
        FolderType.LOCAL.name -> 8
        else -> 6
    }

    /** Orders paths for display: inbox first, then special folders, then A-Z. */
    fun <T> order(items: List<T>, typeOf: (T) -> String, pathOf: (T) -> String): List<T> =
        items.sortedWith(compareBy({ sortRank(typeOf(it)) }, { pathOf(it).lowercase() }))
}
