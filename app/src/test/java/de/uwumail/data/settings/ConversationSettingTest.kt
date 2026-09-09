package de.uwumail.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Switching conversations off, and it staying off.
 *
 * Grouping is on by default, which makes turning it off the deliberate choice
 * of somebody who wants a flat list — and a preference that came back on after
 * a restart would be worse than not offering it, since it would look like the
 * setting had no effect at all. It is written where the list reads it from and
 * read back from disk, not from a value held in memory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversationSettingTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun store() = SettingsStore(context)

    @Before
    fun clear() {
        context.getSharedPreferences("uwumail_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    /** On by default: a reply arriving as a second copy of a subject is the commonest thing a list gets wrong. */
    @Test
    fun `conversations are on until they are turned off`() {
        assertTrue(store().current.groupIntoConversations)
    }

    @Test
    fun `turning them off takes effect immediately`() {
        val store = store()

        store.update { it.copy(groupIntoConversations = false) }

        assertFalse(store.current.groupIntoConversations)
    }

    /** The failure that would make the setting look broken. */
    @Test
    fun `and survives a restart`() {
        store().update { it.copy(groupIntoConversations = false) }

        assertFalse(store().current.groupIntoConversations)
    }

    @Test
    fun `turning them back on survives a restart too`() {
        store().update { it.copy(groupIntoConversations = false) }
        store().update { it.copy(groupIntoConversations = true) }

        assertTrue(store().current.groupIntoConversations)
    }

    /** The list observes the flow, so the change has to reach it without a reopen. */
    @Test
    fun `the change is published to whatever is watching`() {
        val store = store()

        store.update { it.copy(groupIntoConversations = false) }

        assertFalse(store.state.value.groupIntoConversations)
    }

    /** Changing one setting must not quietly reset the others written beside it. */
    @Test
    fun `it does not disturb the settings stored alongside it`() {
        store().update { it.copy(swipeThresholdPercent = 25, readerTextZoom = 150) }

        store().update { it.copy(groupIntoConversations = false) }

        val after = store().current
        assertFalse(after.groupIntoConversations)
        assertTrue(after.swipeThresholdPercent == 25 && after.readerTextZoom == 150)
    }
}
