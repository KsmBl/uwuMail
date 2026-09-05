package de.uwumail.ui.mail

import de.uwumail.data.db.MessageSummary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One day's worth of mail, under the heading the list draws above it. */
data class DaySection(
    val label: String,
    /** Local midnight the day started at; stable, so it makes a good list key. */
    val dayStart: Long,
    val messages: List<MessageSummary>
)

/**
 * Breaks a message list into day sections.
 *
 * The list is already newest-first and the grouping preserves that order, so
 * this is a fold rather than a sort. Every heading carries the full date rather
 * than "Today" or "Yesterday": the point of the headings is to show how much
 * time a scroll has covered, and two relative labels among absolute ones make
 * that harder to read, not easier.
 */
fun groupByDay(
    messages: List<MessageSummary>,
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault()
): List<DaySection> {
    if (messages.isEmpty()) return emptyList()
    val format = SimpleDateFormat("EEEE, dd.MM.yyyy", locale).apply { this.timeZone = timeZone }
    val calendar = Calendar.getInstance(timeZone, locale)

    val sections = mutableListOf<DaySection>()
    var current = mutableListOf<MessageSummary>()
    var currentStart = Long.MIN_VALUE

    for (message in messages) {
        val start = startOfDay(calendar, message.receivedAt)
        if (start != currentStart) {
            if (current.isNotEmpty()) {
                sections += DaySection(format.format(Date(currentStart)), currentStart, current)
            }
            current = mutableListOf()
            currentStart = start
        }
        current += message
    }
    if (current.isNotEmpty()) {
        sections += DaySection(format.format(Date(currentStart)), currentStart, current)
    }
    return sections
}

private fun startOfDay(calendar: Calendar, millis: Long): Long {
    calendar.timeInMillis = millis
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}
