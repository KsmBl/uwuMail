package de.uwumail.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every string the app says exists in both languages.
 *
 * The app claims to be fully translated, and it drifts one hardcoded status
 * message at a time: a snackbar built by string interpolation is invisible to
 * the translator and to the reader of the code, and only shows up as English in
 * the middle of a German screen.
 */
class TranslationTest {

    private fun resources(locale: String): File {
        val relative = "src/main/res/$locale/strings.xml"
        return listOf(File(relative), File("app/$relative")).first { it.exists() }
    }

    private fun namesIn(locale: String, tag: String): Set<String> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(resources(locale))
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length)
            .mapNotNull { nodes.item(it).attributes?.getNamedItem("name")?.nodeValue }
            .toSet()
    }

    /** The app is called uwuMail in every language; a name is not a translation. */
    private val notTranslated = setOf("app_name")

    @Test
    fun `every English string has a German one`() {
        val missing =
            namesIn("values", "string") - namesIn("values-de", "string") - notTranslated

        assertEquals("untranslated strings", emptySet<String>(), missing)
    }

    @Test
    fun `every German string still exists in English`() {
        val orphaned = namesIn("values-de", "string") - namesIn("values", "string")

        assertEquals("German strings with no English original", emptySet<String>(), orphaned)
    }

    @Test
    fun `the only untranslated string is the app's own name`() {
        val missing = namesIn("values", "string") - namesIn("values-de", "string")

        assertEquals(notTranslated, missing)
    }

    @Test
    fun `every plural has both languages too`() {
        assertEquals(
            "untranslated plurals",
            emptySet<String>(),
            namesIn("values", "plurals") - namesIn("values-de", "plurals")
        )
    }

    @Test
    fun `there is a substantial number of strings to check`() {
        // Guards the test itself: an empty or unparsed file would pass silently.
        assertTrue(namesIn("values", "string").size > 200)
    }
}
