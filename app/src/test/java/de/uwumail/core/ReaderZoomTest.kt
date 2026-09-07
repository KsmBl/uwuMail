package de.uwumail.core

import de.uwumail.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinching a message larger or smaller.
 *
 * The WebView's own zoom scaled the page, which pushed a desktop-width mail off
 * the side of the screen, and could only be reached inside the body — a
 * two-line message left nowhere to put two fingers. A pinch now changes the
 * text size, so the words reflow to the screen instead.
 */
class ReaderZoomTest {

    private fun zoom(from: Int, factor: Float) = AppSettings.zoomedBy(from, factor)

    @Test
    fun `mail starts at its normal size`() {
        assertEquals(100, AppSettings().readerTextZoom)
        assertEquals(1f, AppSettings().readerTextScale, 0.0001f)
    }

    @Test
    fun `pinching out makes it bigger`() {
        assertEquals(150, zoom(100, 1.5f))
    }

    @Test
    fun `pinching in makes it smaller`() {
        assertEquals(80, zoom(100, 0.8f))
    }

    @Test
    fun `a pinch that does not move changes nothing`() {
        assertEquals(120, zoom(120, 1f))
    }

    @Test
    fun `pinches compound the way a continuous gesture reports them`() {
        // A pinch arrives as many small factors rather than one large one.
        var size = 100
        repeat(10) { size = zoom(size, 1.05f) }

        assertTrue(size > 150)
    }

    @Test
    fun `it cannot be shrunk past legibility`() {
        assertEquals(AppSettings.MIN_TEXT_ZOOM, zoom(100, 0.01f))
    }

    @Test
    fun `it cannot be grown until one word fills the screen`() {
        assertEquals(AppSettings.MAX_TEXT_ZOOM, zoom(100, 99f))
    }

    @Test
    fun `a size stored out of range is pulled back`() {
        assertEquals(AppSettings.MIN_TEXT_ZOOM, AppSettings.clampTextZoom(-10))
        assertEquals(AppSettings.MAX_TEXT_ZOOM, AppSettings.clampTextZoom(5000))
    }

    @Test
    fun `a shrink at the floor stays put rather than drifting`() {
        val floor = AppSettings.MIN_TEXT_ZOOM

        assertEquals(floor, zoom(floor, 0.9f))
        assertEquals(floor, zoom(zoom(floor, 0.9f), 0.9f))
    }

    @Test
    fun `zooming out then back in returns to about where it began`() {
        assertEquals(100, zoom(zoom(100, 0.5f), 2f))
    }

    @Test
    fun `the scale the plain-text view uses follows the percentage`() {
        assertEquals(1.5f, AppSettings(readerTextZoom = 150).readerTextScale, 0.0001f)
        assertEquals(0.5f, AppSettings(readerTextZoom = 50).readerTextScale, 0.0001f)
    }

    @Test
    fun `the default sits inside the range it is held to`() {
        assertEquals(
            AppSettings.DEFAULT_TEXT_ZOOM,
            AppSettings.clampTextZoom(AppSettings.DEFAULT_TEXT_ZOOM)
        )
    }
}
