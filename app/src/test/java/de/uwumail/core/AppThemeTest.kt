package de.uwumail.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeTest {

    @Test
    fun `an unknown or missing setting falls back to following the system`() {
        assertEquals(AppTheme.SYSTEM, AppTheme.of(null))
        assertEquals(AppTheme.SYSTEM, AppTheme.of(""))
        // A theme from a future version must not leave the app unthemed.
        assertEquals(AppTheme.SYSTEM, AppTheme.of("CATPPUCCIN_FRAPPE"))
    }

    @Test
    fun `a stored name round-trips`() {
        AppTheme.entries.forEach { assertEquals(it, AppTheme.of(it.name)) }
    }

    @Test
    fun `the light themes are not treated as dark ones`() {
        // isDark drives the window's night mode, which is what a WebView reads
        // to decide whether to darken a mail body. A light theme darkening the
        // mail inside it would be the one thing worse than not having it.
        assertFalse(AppTheme.LIGHT.isDark)
        assertFalse(AppTheme.FEMBOY.isDark)
        assertFalse(AppTheme.SYSTEM.isDark)
    }

    @Test
    fun `the dark themes are`() {
        assertTrue(AppTheme.DARK.isDark)
        assertTrue(AppTheme.MOCHA.isDark)
    }

    @Test
    fun `every theme has its own name in the picker`() {
        // The labels are string resources now, so this checks they are set and
        // distinct rather than reading them.
        AppTheme.entries.forEach { assertTrue(it.name, it.label != 0) }
        assertEquals(AppTheme.entries.size, AppTheme.entries.map { it.label }.toSet().size)
    }
}
