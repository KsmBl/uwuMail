package de.uwumail.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * The hours a phone is told not to check in.
 *
 * A Galaxy S20 was fetching nothing on its own and the settings screen said
 * "Watching 1 account for new mail" throughout. Both were true and neither was
 * honest: the service was up, and every watcher inside it was parked because
 * the clock was outside the window. What is asserted here is the condition the
 * screen and the standing notification now report from — that at one in the
 * morning, with a daytime window set, checking is off — and the things that
 * must not be swept up with it.
 */
class PausedWindowTest {

    private fun at(hour: Int, minute: Int = 0, day: Int = Calendar.WEDNESDAY): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.SEPTEMBER)
            set(Calendar.DAY_OF_MONTH, 9)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        check(calendar.get(Calendar.DAY_OF_WEEK) == day)
        return calendar.timeInMillis
    }

    /** The default window, and the hour the report came in at. */
    private val daytime = AppSettings(syncWindowEnabled = true)

    @Test
    fun `just after midnight a daytime window is closed`() {
        assertFalse(daytime.syncAllowedAt(at(0, 1)))
    }

    @Test
    fun `and open again in the morning`() {
        assertTrue(daytime.syncAllowedAt(at(6, 0)))
        assertTrue(daytime.syncAllowedAt(at(12, 30)))
    }

    @Test
    fun `the window is exclusive of its end, so 18 00 is already closed`() {
        assertTrue(daytime.syncAllowedAt(at(17, 59)))
        assertFalse(daytime.syncAllowedAt(at(18, 0)))
    }

    /** With the switch off the hours are ignored, whatever they were left at. */
    @Test
    fun `a window that is switched off never closes`() {
        val off = AppSettings(syncWindowEnabled = false, syncStartMinutes = 6 * 60)

        assertTrue(off.syncAllowedAt(at(0, 1)))
    }

    /** The setting governs unattended checking; pulling down is not that. */
    @Test
    fun `the default is no window at all`() {
        assertFalse(AppSettings().syncWindowEnabled)
        assertTrue(AppSettings().syncAllowedAt(at(3, 0)))
    }

    @Test
    fun `a day that is not chosen is closed all day`() {
        val weekdays = AppSettings(
            syncWindowEnabled = true,
            syncDays = setOf(
                Calendar.MONDAY, Calendar.TUESDAY, Calendar.THURSDAY, Calendar.FRIDAY
            )
        )

        assertFalse(weekdays.syncAllowedAt(at(12, 0)))
    }

    /**
     * The overnight case is the one where "paused until 22:00" would be the
     * wrong thing to say, since checking is on right now.
     */
    @Test
    fun `an overnight window is open in the small hours`() {
        val night = AppSettings(
            syncWindowEnabled = true,
            syncStartMinutes = 22 * 60,
            syncEndMinutes = 6 * 60
        )

        assertTrue(night.syncAllowedAt(at(0, 1)))
        assertTrue(night.syncAllowedAt(at(23, 0)))
        assertFalse(night.syncAllowedAt(at(12, 0)))
    }
}
