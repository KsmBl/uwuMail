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

    /** What happened, for the caller to put into words in its own language. */
    enum class Outcome { NOTHING_ATTACHED, ALL_FAILED, SAVED }

    val outcome: Outcome
        get() = when {
            saved == 0 && missing == 0 -> Outcome.NOTHING_ATTACHED
            saved == 0 -> Outcome.ALL_FAILED
            else -> Outcome.SAVED
        }

    /** A partial result has something to report beyond the count that worked. */
    val partial: Boolean get() = saved > 0 && missing > 0
}
