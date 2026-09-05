package de.uwumail.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePrefilterTest {

    private val policy = RemoteImagePolicy(filterTiny = true, minWidth = 10, minHeight = 10)

    private fun strip(html: String, with: RemoteImagePolicy = policy) =
        ImagePrefilter.strip(html, with)

    @Test
    fun `removes the classic one pixel beacon before it can be fetched`() {
        val result = strip("""<img src="https://t.example/open?id=7" width="1" height="1">""")
        assertFalse(result.html.contains("t.example"))
        assertEquals(1, result.tinyRemoved)
        assertEquals(0, result.remoteRemaining)
    }

    @Test
    fun `removes a beacon sized only in css`() {
        val result = strip(
            """<img src="https://t.example/p" style="width:1px;height:1px;border:0">"""
        )
        assertFalse(result.html.contains("t.example"))
        assertEquals(1, result.tinyRemoved)
    }

    @Test
    fun `removes a beacon hidden with display none`() {
        val result = strip("""<img src="https://t.example/p" style="display:none">""")
        assertFalse(result.html.contains("t.example"))
        assertEquals(1, result.hiddenRemoved)
    }

    @Test
    fun `removes a beacon inside a hidden wrapper`() {
        val result = strip(
            """<div style="display:none"><img src="https://t.example/p" width="100"></div>"""
        )
        assertFalse(result.html.contains("t.example"))
        assertEquals(1, result.hiddenRemoved)
    }

    @Test
    fun `removes a beacon hidden by a stylesheet class`() {
        val result = strip(
            """
            <style>.hide { display:none } .x { color: red }</style>
            <img class="hide" src="https://t.example/p">
            """
        )
        assertFalse(result.html.contains("t.example"))
        assertEquals(1, result.hiddenRemoved)
    }

    @Test
    fun `removes a beacon sized by a stylesheet id`() {
        val result = strip(
            """
            <style>#beacon { width: 1px; height: 1px; }</style>
            <img id="beacon" src="https://t.example/p">
            """
        )
        assertFalse(result.html.contains("t.example"))
        assertEquals(1, result.tinyRemoved)
    }

    @Test
    fun `removes zero-sized and transparent images`() {
        assertEquals(1, strip("""<img src="https://t.example/a" height="0">""").hiddenRemoved)
        assertEquals(
            1,
            strip("""<img src="https://t.example/b" style="opacity:0">""").hiddenRemoved
        )
        assertEquals(
            1,
            strip("""<img src="https://t.example/c" style="visibility:hidden">""").hiddenRemoved
        )
    }

    @Test
    fun `keeps a real image`() {
        val html = """<img src="https://cdn.example/banner.png" width="600" height="200">"""
        val result = strip(html)
        assertTrue(result.html.contains("cdn.example"))
        assertEquals(0, result.removed)
        assertEquals(1, result.remoteRemaining)
    }

    @Test
    fun `keeps an image whose size it cannot read`() {
        // Percentages and other units depend on layout, so nothing is assumed.
        val result = strip(
            """<img src="https://cdn.example/a.png" width="100%" style="height:2em">"""
        )
        assertTrue(result.html.contains("cdn.example"))
        assertEquals(0, result.removed)
    }

    @Test
    fun `keeps an image only hidden in some other viewport`() {
        // The rule is inside a media query, so it says nothing about this one.
        val result = strip(
            """
            <style>@media (max-width: 100px) { .responsive { display: none } }</style>
            <img class="responsive" src="https://cdn.example/a.png" width="600">
            """
        )
        assertTrue(result.html.contains("cdn.example"))
        assertEquals(0, result.removed)
    }

    @Test
    fun `leaves the rest of the message alone`() {
        val result = strip(
            """<p>Hello <b>there</b></p><img src="https://t.example/p" width="1" height="1">"""
        )
        assertTrue(result.html.contains("Hello"))
        assertTrue(result.html.contains("<b>there</b>"))
    }

    @Test
    fun `honours the configured minimum`() {
        val strict = RemoteImagePolicy(filterTiny = true, minWidth = 50, minHeight = 50)
        val html = """<img src="https://cdn.example/a.png" width="40" height="40">"""
        assertEquals(0, strip(html).tinyRemoved)
        assertEquals(1, strip(html, strict).tinyRemoved)
    }

    @Test
    fun `with the size filter off it still refuses to fetch what is never shown`() {
        val off = policy.copy(filterTiny = false)
        val result = strip(
            """
            <img src="https://t.example/hidden" style="display:none">
            <img src="https://t.example/tiny" width="1" height="1">
            """,
            off
        )
        assertFalse(result.html.contains("t.example/hidden"))
        assertTrue(result.html.contains("t.example/tiny"))
        assertEquals(1, result.hiddenRemoved)
        assertEquals(0, result.tinyRemoved)
    }

    @Test
    fun `counts what a body would still fetch`() {
        val result = strip(
            """
            <img src="https://cdn.example/a.png" width="600">
            <img src="cid:inline@mail" width="600">
            <img src="data:image/png;base64,AAAA" width="600">
            <img src="https://t.example/p" width="1" height="1">
            """
        )
        // Only the one remote image that survives; cid: and data: are not fetched.
        assertEquals(1, result.remoteRemaining)
    }

    @Test
    fun `survives markup it cannot parse`() {
        val result = strip("<img src=https://t.example/p width=1 height=1><style>{{{</style>")
        assertEquals(1, result.tinyRemoved)
    }
}
