package de.uwumail.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The swipe distance surviving a restart.
 *
 * It is read straight out of SharedPreferences by the list, so a value written
 * by a later version, or left behind by one, has to come back usable rather
 * than as whatever integer happens to be on disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SwipeThresholdStorageTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun store() = SettingsStore(context)

    private fun writeRaw(percent: Int) {
        context.getSharedPreferences("uwumail_settings", Context.MODE_PRIVATE)
            .edit()
            .putInt("swipe_threshold_percent", percent)
            .commit()
    }

    @Test
    fun `a chosen distance is read back after a restart`() {
        store().update { it.copy(swipeThresholdPercent = 25) }

        assertEquals(25, store().current.swipeThresholdPercent)
    }

    @Test
    fun `a fresh install starts at half the row`() {
        assertEquals(
            AppSettings.DEFAULT_SWIPE_PERCENT,
            store().current.swipeThresholdPercent
        )
    }

    @Test
    fun `a hair trigger written to disk is pulled back on read`() {
        writeRaw(2)

        assertEquals(AppSettings.MIN_SWIPE_PERCENT, store().current.swipeThresholdPercent)
    }

    @Test
    fun `an impossible distance written to disk is pulled back on read`() {
        writeRaw(400)

        assertEquals(AppSettings.MAX_SWIPE_PERCENT, store().current.swipeThresholdPercent)
    }

    @Test
    fun `changing the distance leaves the swipe actions alone`() {
        val store = store()
        val before = store.current.swipeRight to store.current.swipeLeft

        store.update { it.copy(swipeThresholdPercent = 70) }

        assertEquals(before, store.current.swipeRight to store.current.swipeLeft)
    }
}
