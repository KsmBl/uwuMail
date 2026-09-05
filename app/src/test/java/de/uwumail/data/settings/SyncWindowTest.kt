package de.uwumail.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class SyncWindowTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun calendar() = Calendar.getInstance(utc, Locale.ENGLISH)

    /** 2026-03-09 is a Monday. */
    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(2026, Calendar.MARCH, day, hour, minute, 0)
        }.timeInMillis

    private fun allows(settings: AppSettings, millis: Long) =
        settings.syncAllowedAt(millis, calendar())

    private val nineToFive = AppSettings(
        syncWindowEnabled = true,
        syncStartMinutes = 6 * 60,
        syncEndMinutes = 18 * 60
    )

    @Test
    fun `an off window allows every moment`() {
        val off = AppSettings(syncWindowEnabled = false, syncDays = setOf(Calendar.MONDAY))
        assertTrue(allows(off, at(14, 3)))
    }

    @Test
    fun `inside the hours is allowed`() {
        assertTrue(allows(nineToFive, at(9, 6, 0)))
        assertTrue(allows(nineToFive, at(9, 12, 30)))
        assertTrue(allows(nineToFive, at(9, 17, 59)))
    }

    @Test
    fun `outside the hours is not`() {
        assertFalse(allows(nineToFive, at(9, 5, 59)))
        assertFalse(allows(nineToFive, at(9, 18, 0)))
        assertFalse(allows(nineToFive, at(9, 23, 30)))
    }

    @Test
    fun `a day that is not selected is never allowed`() {
        val weekdays = nineToFive.copy(
            syncDays = setOf(
                Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY
            )
        )
        // 14 March 2026 is a Saturday, 15 March a Sunday.
        assertFalse(allows(weekdays, at(14, 12)))
        assertFalse(allows(weekdays, at(15, 12)))
        assertTrue(allows(weekdays, at(13, 12)))
    }

    @Test
    fun `an overnight window covers the hours after midnight`() {
        val night = AppSettings(
            syncWindowEnabled = true,
            syncStartMinutes = 22 * 60,
            syncEndMinutes = 6 * 60
        )
        assertTrue(allows(night, at(9, 22, 0)))
        assertTrue(allows(night, at(9, 23, 59)))
        assertTrue(allows(night, at(10, 0, 30)))
        assertTrue(allows(night, at(10, 5, 59)))
        assertFalse(allows(night, at(10, 6, 0)))
        assertFalse(allows(night, at(10, 12, 0)))
    }

    @Test
    fun `an overnight window's tail belongs to the day it started on`() {
        val fridayNight = AppSettings(
            syncWindowEnabled = true,
            syncDays = setOf(Calendar.FRIDAY),
            syncStartMinutes = 22 * 60,
            syncEndMinutes = 6 * 60
        )
        // Friday 13 March 2026 at 23:00, and the Saturday morning it runs into.
        assertTrue(allows(fridayNight, at(13, 23, 0)))
        assertTrue(allows(fridayNight, at(14, 2, 0)))
        // Saturday evening is not Friday's window.
        assertFalse(allows(fridayNight, at(14, 23, 0)))
    }

    @Test
    fun `a window with equal start and end runs the whole night through`() {
        val allNight = AppSettings(
            syncWindowEnabled = true,
            syncStartMinutes = 8 * 60,
            syncEndMinutes = 8 * 60
        )
        assertTrue(allows(allNight, at(9, 8, 0)))
        assertTrue(allows(allNight, at(9, 23, 0)))
        assertTrue(allows(allNight, at(10, 7, 59)))
    }
}
