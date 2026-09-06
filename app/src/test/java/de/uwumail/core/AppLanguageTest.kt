package de.uwumail.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {

    @Test
    fun `following the system is the absence of a choice`() {
        // An empty locale list is how Android says "no per-app language", so
        // this option must not carry a tag of its own.
        assertNull(AppLanguage.SYSTEM.tag)
    }

    @Test
    fun `each language carries the tag Android expects`() {
        assertEquals("en", AppLanguage.ENGLISH.tag)
        assertEquals("de", AppLanguage.GERMAN.tag)
    }

    @Test
    fun `the tags match the translations that are actually shipped`() {
        // A language offered here with no values-xx folder behind it would
        // switch to a language that does not exist.
        val shipped = setOf(null, "en", "de")
        AppLanguage.entries.forEach { assertTrue(it.name, it.tag in shipped) }
    }

    @Test
    fun `every language has its own entry in the picker`() {
        AppLanguage.entries.forEach { assertTrue(it.name, it.label != 0) }
        assertEquals(AppLanguage.entries.size, AppLanguage.entries.map { it.label }.toSet().size)
        assertEquals(AppLanguage.entries.size, AppLanguage.entries.map { it.tag }.toSet().size)
    }
}
