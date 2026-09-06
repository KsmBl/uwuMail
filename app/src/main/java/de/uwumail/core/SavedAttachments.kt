package de.uwumail.core

/**
 * What came of asking a selection for its attachments.
 *
 * [missing] is counted rather than folded into a smaller [saved], so a partial
 * result can say that something failed instead of quietly reporting a smaller
 * number as though it were the whole answer.
 */
data class SavedAttachments(
    val saved: Int = 0,
    val missing: Int = 0,
    val messagesWithNone: Int = 0
) {

    /** The one line the user sees when it is over. */
    fun summary(selectionSize: Int, folder: String): String = when {
        saved == 0 && missing == 0 ->
            if (selectionSize == 1) "Nothing attached to that message"
            else "Nothing attached to those messages"
        saved == 0 -> if (missing == 1) {
            "Could not fetch that attachment"
        } else {
            "Could not fetch those $missing attachments"
        }
        else -> buildString {
            append(saved)
            append(if (saved == 1) " file saved to " else " files saved to ")
            append(folder)
            if (missing > 0) append(" · $missing could not be fetched")
        }
    }
}
