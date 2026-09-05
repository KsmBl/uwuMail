package de.uwumail.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteImagePolicyTest {

    private val default = RemoteImagePolicy(filterTiny = true, minWidth = 10, minHeight = 10)

    @Test
    fun `blocks the classic one pixel beacon`() {
        assertTrue(default.isTrackingPixel(1, 1))
    }

    @Test
    fun `blocks an image that is thin in one dimension only`() {
        assertTrue(default.isTrackingPixel(1, 600))
        assertTrue(default.isTrackingPixel(600, 1))
    }

    @Test
    fun `allows an image exactly at the limit`() {
        assertFalse(default.isTrackingPixel(10, 10))
    }

    @Test
    fun `allows a real image`() {
        assertFalse(default.isTrackingPixel(640, 480))
    }

    @Test
    fun `allows everything when the filter is off`() {
        val off = default.copy(filterTiny = false)
        assertFalse(off.isTrackingPixel(1, 1))
    }

    @Test
    fun `never blocks an image it could not measure`() {
        // Decoders report 0 or -1 for formats they do not understand, such as SVG.
        assertFalse(default.isTrackingPixel(0, 0))
        assertFalse(default.isTrackingPixel(-1, -1))
    }

    @Test
    fun `honours a custom limit`() {
        val strict = RemoteImagePolicy(filterTiny = true, minWidth = 50, minHeight = 20)
        assertTrue(strict.isTrackingPixel(49, 100))
        assertTrue(strict.isTrackingPixel(100, 19))
        assertFalse(strict.isTrackingPixel(50, 20))
    }

    @Test
    fun `only images are fetched for a mail body`() {
        assertTrue(default.allowsContentType("image/png"))
        assertTrue(default.allowsContentType("image/jpeg; charset=binary"))
        assertTrue(default.allowsContentType("IMAGE/GIF"))
        assertFalse(default.allowsContentType("text/css"))
        assertFalse(default.allowsContentType("font/woff2"))
        assertFalse(default.allowsContentType("application/javascript"))
        assertFalse(default.allowsContentType(null))
    }
}
