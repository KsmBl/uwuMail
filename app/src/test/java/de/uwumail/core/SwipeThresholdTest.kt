package de.uwumail.core

import de.uwumail.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How far a swipe must travel before it counts.
 *
 * It was a hardcoded half of the row, chosen because these actions delete mail
 * and one done by accident costs more than one that had to be meant. Half a
 * screen is a different distance on a 5-inch phone held one-handed than on a
 * tablet, so it is now a setting — held inside a range, since a threshold near
 * zero turns a brush past the screen into a deletion and one near the full
 * width cannot be reached at all.
 */
class SwipeThresholdTest {

    private fun fraction(percent: Int) =
        AppSettings(swipeThresholdPercent = percent).swipeThresholdFraction

    @Test
    fun `the default is still half the row`() {
        assertEquals(50, AppSettings().swipeThresholdPercent)
        assertEquals(0.5f, AppSettings().swipeThresholdFraction, 0.0001f)
    }

    @Test
    fun `a quarter of the screen is a quarter of the row`() {
        assertEquals(0.25f, fraction(25), 0.0001f)
    }

    @Test
    fun `three quarters is three quarters`() {
        assertEquals(0.75f, fraction(75), 0.0001f)
    }

    @Test
    fun `a hair trigger is pulled back to the minimum`() {
        assertEquals(AppSettings.MIN_SWIPE_PERCENT / 100f, fraction(1), 0.0001f)
    }

    @Test
    fun `zero cannot delete mail by a touch`() {
        assertTrue(fraction(0) >= AppSettings.MIN_SWIPE_PERCENT / 100f)
    }

    @Test
    fun `a negative value stored by mistake is still usable`() {
        assertEquals(AppSettings.MIN_SWIPE_PERCENT / 100f, fraction(-40), 0.0001f)
    }

    @Test
    fun `a threshold wider than the screen is pulled back to the maximum`() {
        assertEquals(AppSettings.MAX_SWIPE_PERCENT / 100f, fraction(140), 0.0001f)
    }

    @Test
    fun `the whole screen is not a reachable setting`() {
        assertTrue(fraction(100) < 1f)
    }

    @Test
    fun `the ends of the range are themselves allowed`() {
        assertEquals(AppSettings.MIN_SWIPE_PERCENT, AppSettings.clampSwipePercent(10))
        assertEquals(AppSettings.MAX_SWIPE_PERCENT, AppSettings.clampSwipePercent(90))
    }

    @Test
    fun `the range is the right way round and worth having`() {
        assertTrue(AppSettings.MIN_SWIPE_PERCENT < AppSettings.MAX_SWIPE_PERCENT)
        assertTrue(AppSettings.MIN_SWIPE_PERCENT > 0)
        assertTrue(AppSettings.MAX_SWIPE_PERCENT < 100)
    }

    @Test
    fun `the default sits inside the range it is held to`() {
        assertEquals(
            AppSettings.DEFAULT_SWIPE_PERCENT,
            AppSettings.clampSwipePercent(AppSettings.DEFAULT_SWIPE_PERCENT)
        )
    }

    @Test
    fun `a lower setting always means a shorter swipe`() {
        val distances = (AppSettings.MIN_SWIPE_PERCENT..AppSettings.MAX_SWIPE_PERCENT step 5)
            .map { fraction(it) }

        assertEquals(distances.sorted(), distances)
    }
}
