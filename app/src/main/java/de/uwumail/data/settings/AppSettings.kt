package de.uwumail.data.settings

import de.uwumail.core.AppTheme
import de.uwumail.core.SwipeAction
import java.util.Calendar

/**
 * Every app-wide preference, in one immutable snapshot.
 *
 * Defaults are the privacy-preserving choice: remote content stays blocked
 * until asked for, tracking parameters are questioned, and mail is never
 * fetched outside a window the user set.
 */
data class AppSettings(
    /** Offer a one-tap unsubscribe when a message advertises one. */
    val unsubscribeBanner: Boolean = true,

    /** Ask before opening a link that carries tracking parameters. */
    val askStripTracking: Boolean = true,

    /** Fetch the bodies of unread mail in the background while the list is open. */
    val preloadUnread: Boolean = true,

    /** Remote images stay blocked until the banner in a message is tapped. */
    val blockRemoteImages: Boolean = true,

    /** JavaScript in mail bodies. Off is the only safe setting; it is exposed anyway. */
    val allowJavaScript: Boolean = false,

    /** Drop images below [minImageWidth] x [minImageHeight] — tracking pixels. */
    val filterTinyImages: Boolean = true,
    val minImageWidth: Int = 10,
    val minImageHeight: Int = 10,

    /** Which colours the app wears. */
    val theme: AppTheme = AppTheme.SYSTEM,

    /**
     * What a swipe across a message in the list does, per direction. The
     * defaults are the two everyone expects, and either can be set to
     * [SwipeAction.NONE] to turn that direction off.
     */
    val swipeRight: SwipeAction = SwipeAction.ARCHIVE,
    val swipeLeft: SwipeAction = SwipeAction.SELECT,

    /**
     * How far across the row a swipe must travel before it counts, as a
     * percentage of the screen's width.
     *
     * Half by default, which is deliberately a long way: these actions move and
     * delete mail, and one done by accident costs more than one that had to be
     * meant. Hands and screens differ though, so it is a setting rather than a
     * judgement — see [swipeThresholdFraction] for the range it is held to.
     */
    val swipeThresholdPercent: Int = DEFAULT_SWIPE_PERCENT,

    /**
     * Gather a list into conversations, one row per thread.
     *
     * On, because a reply arriving as a second copy of a subject you have
     * already read is the commonest thing a mail list gets wrong. It is a
     * setting because the flat list is a defensible preference, not a mistake.
     */
    val groupIntoConversations: Boolean = true,

    /** Set by the easter egg; until then the gravity menu entry does not exist. */
    val gravityUnlocked: Boolean = false,

    /** Restrict background mail checks to [syncDays] between the two times. */
    val syncWindowEnabled: Boolean = false,
    /** [Calendar.DAY_OF_WEEK] values, so Sunday is 1 and Saturday is 7. */
    val syncDays: Set<Int> = ALL_DAYS,
    /** Minutes since midnight, local time. */
    val syncStartMinutes: Int = 6 * 60,
    val syncEndMinutes: Int = 18 * 60
) {

    /**
     * [swipeThresholdPercent] as the fraction the list actually swipes by, held
     * inside a range whatever was stored: a threshold near zero turns a brush
     * past the screen into a deletion, and one near the full width cannot be
     * reached on a wide phone at all.
     */
    val swipeThresholdFraction: Float
        get() = clampSwipePercent(swipeThresholdPercent) / 100f

    /** What a message body is allowed to fetch, as the image code wants it. */
    fun imagePolicy() = de.uwumail.mail.RemoteImagePolicy(
        filterTiny = filterTinyImages,
        minWidth = minImageWidth,
        minHeight = minImageHeight
    )

    /**
     * Whether background mail checks may run at [millis].
     *
     * A window whose end is at or before its start is read as spanning
     * midnight, so 22:00-06:00 means the night rather than nothing at all.
     */
    fun syncAllowedAt(millis: Long, calendar: Calendar = Calendar.getInstance()): Boolean {
        if (!syncWindowEnabled) return true
        calendar.timeInMillis = millis
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val day = calendar.get(Calendar.DAY_OF_WEEK)
        val overnight = syncEndMinutes <= syncStartMinutes
        return if (overnight) {
            // The tail after midnight belongs to the day the window started on.
            (day in syncDays && minutes >= syncStartMinutes) ||
                (previousDay(day) in syncDays && minutes < syncEndMinutes)
        } else {
            day in syncDays && minutes >= syncStartMinutes && minutes < syncEndMinutes
        }
    }

    companion object {
        /** Half the row, the distance the list swiped by before it was a setting. */
        const val DEFAULT_SWIPE_PERCENT = 50

        /**
         * The narrowest and widest a swipe threshold may be set to.
         *
         * Below the minimum an accidental brush across the screen deletes mail;
         * above the maximum the gesture runs out of screen before it commits.
         */
        const val MIN_SWIPE_PERCENT = 10
        const val MAX_SWIPE_PERCENT = 90

        fun clampSwipePercent(percent: Int): Int =
            percent.coerceIn(MIN_SWIPE_PERCENT, MAX_SWIPE_PERCENT)

        val ALL_DAYS: Set<Int> = (Calendar.SUNDAY..Calendar.SATURDAY).toSet()

        /** Monday first, the way the settings screen lists them. */
        val DAY_ORDER = listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        )

        fun dayLabel(day: Int): String = when (day) {
            Calendar.MONDAY -> "Mon"
            Calendar.TUESDAY -> "Tue"
            Calendar.WEDNESDAY -> "Wed"
            Calendar.THURSDAY -> "Thu"
            Calendar.FRIDAY -> "Fri"
            Calendar.SATURDAY -> "Sat"
            else -> "Sun"
        }

        private fun previousDay(day: Int) = if (day == Calendar.SUNDAY) Calendar.SATURDAY else day - 1

        fun formatTime(minutes: Int): String =
            "%02d:%02d".format(minutes / 60, minutes % 60)
    }
}
