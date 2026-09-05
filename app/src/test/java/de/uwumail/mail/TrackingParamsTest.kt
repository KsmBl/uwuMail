package de.uwumail.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingParamsTest {

    @Test
    fun `strips the utm family and keeps everything else`() {
        assertEquals(
            "https://shop.example/p?id=42&size=l",
            TrackingParams.strip(
                "https://shop.example/p?id=42&utm_source=news&size=l&utm_campaign=spring"
            )
        )
    }

    @Test
    fun `strips the well known click ids`() {
        assertEquals(
            "https://example.com/a",
            TrackingParams.strip("https://example.com/a?fbclid=x&gclid=y&mc_eid=z&msclkid=w")
        )
    }

    @Test
    fun `drops the question mark when nothing survives`() {
        assertEquals("https://example.com/a", TrackingParams.strip("https://example.com/a?utm_source=n"))
    }

    @Test
    fun `keeps the fragment`() {
        assertEquals(
            "https://example.com/a?id=1#section",
            TrackingParams.strip("https://example.com/a?id=1&utm_medium=mail#section")
        )
    }

    @Test
    fun `keeps a fragment when the whole query goes`() {
        assertEquals(
            "https://example.com/a#top",
            TrackingParams.strip("https://example.com/a?utm_source=n#top")
        )
    }

    @Test
    fun `leaves a clean url untouched`() {
        val url = "https://example.com/article?page=2&sort=new"
        assertEquals(url, TrackingParams.strip(url))
        assertFalse(TrackingParams.hasTracking(url))
    }

    @Test
    fun `leaves a url with no query untouched`() {
        assertEquals("https://example.com/a", TrackingParams.strip("https://example.com/a"))
    }

    @Test
    fun `never touches the path, where senders encode real targets`() {
        val url = "https://click.example/CL0/https%3A%2F%2Freal.example%2Fp/1/abc?utm_source=n"
        assertEquals(
            "https://click.example/CL0/https%3A%2F%2Freal.example%2Fp/1/abc",
            TrackingParams.strip(url)
        )
    }

    @Test
    fun `is case insensitive about parameter names`() {
        assertEquals("https://example.com/a", TrackingParams.strip("https://example.com/a?UTM_Source=n&FBCLID=x"))
    }

    @Test
    fun `keeps a valueless parameter that is not tracking`() {
        assertEquals(
            "https://example.com/a?debug",
            TrackingParams.strip("https://example.com/a?debug&utm_source=n")
        )
    }

    @Test
    fun `leaves non http schemes alone`() {
        val mailto = "mailto:leave@example.com?subject=stop"
        assertEquals(mailto, TrackingParams.strip(mailto))
        assertFalse(TrackingParams.hasTracking(mailto))
    }

    @Test
    fun `lists the tracking parameters it found`() {
        assertEquals(
            listOf("utm_source", "fbclid"),
            TrackingParams.findIn("https://example.com/a?utm_source=n&id=3&fbclid=x")
        )
    }

    @Test
    fun `recognises prefixed families`() {
        assertTrue(TrackingParams.isTracking("utm_content"))
        assertTrue(TrackingParams.isTracking("mtm_campaign"))
        assertTrue(TrackingParams.isTracking("at_medium"))
        assertFalse(TrackingParams.isTracking("format"))
        assertFalse(TrackingParams.isTracking("q"))
    }

    @Test
    fun `reports the host without www`() {
        assertEquals("example.com", TrackingParams.hostOf("https://www.example.com/a?b=1"))
    }
}
