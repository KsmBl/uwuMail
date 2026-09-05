package de.uwumail.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsubscribeTest {

    @Test
    fun `prefers the https entry over mailto`() {
        val target = Unsubscribe.fromHeaders(
            mapOf(
                "list-unsubscribe" to listOf(
                    "<mailto:leave@list.example>, <https://list.example/u?id=7>"
                )
            )
        )
        assertEquals("https://list.example/u?id=7", target?.url)
        assertFalse(target!!.isMailto)
    }

    @Test
    fun `falls back to mailto when that is all there is`() {
        val target = Unsubscribe.fromHeaders(
            mapOf("list-unsubscribe" to listOf("<mailto:leave@list.example?subject=stop>"))
        )
        assertEquals("mailto:leave@list.example?subject=stop", target?.url)
        assertTrue(target!!.isMailto)
    }

    @Test
    fun `marks one-click senders`() {
        val target = Unsubscribe.fromHeaders(
            mapOf(
                "list-unsubscribe" to listOf("<https://list.example/u>"),
                "list-unsubscribe-post" to listOf("List-Unsubscribe=One-Click")
            )
        )
        assertTrue(target!!.oneClick)
    }

    @Test
    fun `ignores comment text outside the angle brackets`() {
        val target = Unsubscribe.fromHeaders(
            mapOf("list-unsubscribe" to listOf("(click here) <https://list.example/u>"))
        )
        assertEquals("https://list.example/u", target?.url)
    }

    @Test
    fun `no header and no body means no banner`() {
        assertNull(Unsubscribe.find(emptyMap(), "<p>Hello</p>", "Hello"))
    }

    @Test
    fun `finds an unsubscribe link in the html body`() {
        val html = """
            <p>News</p>
            <a href="https://track.example/click?x=1">Read more</a>
            <a href="https://news.example/opt-out/abc">Unsubscribe</a>
        """.trimIndent()
        val target = Unsubscribe.find(emptyMap(), html, null)
        assertEquals("https://news.example/opt-out/abc", target?.url)
        assertTrue(target!!.fromBody)
    }

    @Test
    fun `finds a german unsubscribe link`() {
        val html = """<a href="https://news.example/x">Newsletter abbestellen</a>"""
        assertEquals("https://news.example/x", Unsubscribe.find(emptyMap(), html, null)?.url)
    }

    @Test
    fun `finds a plain text unsubscribe url near the word`() {
        val plain = "Thanks for reading.\n\nTo unsubscribe visit https://news.example/u/9\n"
        assertEquals("https://news.example/u/9", Unsubscribe.find(emptyMap(), null, plain)?.url)
    }

    @Test
    fun `header wins over a body link`() {
        val target = Unsubscribe.find(
            mapOf("list-unsubscribe" to listOf("<https://header.example/u>")),
            """<a href="https://body.example/unsubscribe">Unsubscribe</a>""",
            null
        )
        assertEquals("https://header.example/u", target?.url)
        assertFalse(target!!.fromBody)
    }

    @Test
    fun `does not follow a javascript href`() {
        val html = """<a href="javascript:alert(1)">Unsubscribe</a>"""
        assertNull(Unsubscribe.find(emptyMap(), html, null))
    }
}
