package de.uwumail.ui.mail

import de.uwumail.data.db.MessageSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class DateGroupingTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val english = Locale.ENGLISH

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun message(id: Long, receivedAt: Long) = MessageSummary(
        id = id,
        accountId = 1,
        folderId = 1,
        uid = id,
        subject = "s$id",
        fromName = null,
        fromAddress = "a@b.c",
        toList = "",
        receivedAt = receivedAt,
        seen = false,
        flagged = false,
        answered = false,
        hasAttachments = false,
        sizeBytes = 0,
        preview = "",
        isLocal = false,
        bodyDownloaded = false,
        spam = false
    )

    private fun group(vararg messages: MessageSummary) =
        groupByDay(messages.toList(), english, utc)

    @Test
    fun `heads each day with its weekday and date`() {
        val sections = group(message(1, at(2001, 1, 1, 12)))
        assertEquals(1, sections.size)
        assertEquals("Monday, 01.01.2001", sections.first().label)
    }

    @Test
    fun `puts every message of one day under one heading`() {
        val sections = group(
            message(1, at(2026, 3, 10, 18)),
            message(2, at(2026, 3, 10, 9)),
            message(3, at(2026, 3, 10, 0, 1))
        )
        assertEquals(1, sections.size)
        assertEquals(listOf(1L, 2L, 3L), sections.first().messages.map { it.id })
    }

    @Test
    fun `starts a new section at midnight`() {
        val sections = group(
            message(1, at(2026, 3, 10, 0, 0)),
            message(2, at(2026, 3, 9, 23, 59))
        )
        assertEquals(2, sections.size)
        assertEquals("Tuesday, 10.03.2026", sections[0].label)
        assertEquals("Monday, 09.03.2026", sections[1].label)
    }

    @Test
    fun `keeps the newest-first order the list arrives in`() {
        val sections = group(
            message(1, at(2026, 3, 12, 8)),
            message(2, at(2026, 3, 11, 8)),
            message(3, at(2026, 3, 11, 7)),
            message(4, at(2026, 3, 9, 8))
        )
        assertEquals(listOf("12.03.2026", "11.03.2026", "09.03.2026"), sections.map {
            it.label.substringAfter(", ")
        })
        assertEquals(listOf(2L, 3L), sections[1].messages.map { it.id })
    }

    @Test
    fun `revisiting a day makes a second section rather than merging`() {
        // Only possible with out-of-order input, but it must not lose messages.
        val sections = group(
            message(1, at(2026, 3, 12, 8)),
            message(2, at(2026, 3, 11, 8)),
            message(3, at(2026, 3, 12, 7))
        )
        assertEquals(3, sections.size)
        assertEquals(3, sections.sumOf { it.messages.size })
    }

    @Test
    fun `an empty list has no headings`() {
        assertTrue(groupByDay(emptyList(), english, utc).isEmpty())
    }

    @Test
    fun `the section key is the local midnight of that day`() {
        val sections = group(message(1, at(2026, 3, 10, 15, 30)))
        assertEquals(at(2026, 3, 10, 0, 0), sections.first().dayStart)
    }

    @Test
    fun `mail with no date still gets a heading rather than being dropped`() {
        val sections = group(message(1, 0))
        assertEquals(1, sections.size)
        assertEquals(1, sections.first().messages.size)
    }
}
