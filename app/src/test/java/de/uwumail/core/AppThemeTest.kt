package de.uwumail.core

import org.junit.Assert.assertEquals
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
    fun `every theme is named for the picker`() {
        AppTheme.entries.forEach { assertTrue(it.name, it.label.isNotBlank()) }
        assertEquals(AppTheme.entries.size, AppTheme.entries.map { it.label }.toSet().size)
    }
}
