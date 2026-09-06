package de.uwumail.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegexBuilderTest {

    @Test
    fun `generalises CI subjects into a pattern that matches every sample`() {
        val samples = listOf(
            "[myorg/backend] Run failed: CI #482",
            "[myorg/backend] Run failed: CI #1037",
            "[myorg/backend] Run failed: CI #7"
        )
        val pattern = RegexBuilder.fromSamples(samples)
        assertNotNull("expected a pattern for structurally identical subjects", pattern)
        val regex = Regex(pattern!!)
        samples.forEach { assertTrue("$pattern did not match $it", regex.containsMatchIn(it)) }
        // The run number varies and must have been generalised, not baked in.
        assertTrue("expected a digit class, got: $pattern", pattern.contains("""\d+"""))
        assertTrue(regex.containsMatchIn("[myorg/backend] Run failed: CI #99999"))
    }

    @Test
    fun `does not match unrelated subjects`() {
        val pattern = RegexBuilder.fromSamples(
            listOf(
                "[myorg/backend] Run failed: CI #482",
                "[myorg/backend] Run failed: CI #1037"
            )
        )
        val regex = Regex(pattern!!)
        assertTrue(!regex.containsMatchIn("Your invoice for August"))
        assertTrue(!regex.containsMatchIn("[myorg/frontend] Run succeeded: CI #12"))
    }

    @Test
    fun `varying words fall back to a non-space class rather than dot star`() {
        val pattern = RegexBuilder.fromSamples(
            listOf("Deploy to staging finished", "Deploy to production finished")
        )
        assertNotNull(pattern)
        val regex = Regex(pattern!!)
        assertTrue(regex.containsMatchIn("Deploy to staging finished"))
        assertTrue(regex.containsMatchIn("Deploy to production finished"))
    }

    @Test
    fun `returns null when samples share nothing meaningful`() {
        assertNull(RegexBuilder.fromSamples(listOf("Invoice 2024", "Dinner Friday?")))
    }

    @Test
    fun `finds the longest shared substring`() {
        val common = RegexBuilder.longestCommonSubstring(
            listOf(
                "Alert: disk usage on web-01",
                "Alert: disk usage on web-02",
                "Alert: disk usage on db-17"
            )
        )
        assertEquals("Alert: disk usage on ", common)
    }

    @Test
    fun `finds a common prefix but ignores a too-short one`() {
        assertEquals(
            "Re: [ticket-",
            RegexBuilder.commonPrefix(listOf("Re: [ticket-9] hi", "Re: [ticket-42] yo"))
        )
        assertNull(RegexBuilder.commonPrefix(listOf("ab-one", "ab-two")))
    }

    @Test
    fun `escapes regex metacharacters in literal segments`() {
        val pattern = RegexBuilder.fromSamples(
            listOf("Backup (nightly) done in 12s", "Backup (nightly) done in 340s")
        )
        assertNotNull(pattern)
        assertTrue(Regex(pattern!!).containsMatchIn("Backup (nightly) done in 5s"))
    }

    @Test
    fun `finds the longest shared run and prefers the earliest of equal length`() {
        assertEquals(
            " shared-run ",
            RegexBuilder.longestCommonSubstring(
                listOf("one shared-run here", "two shared-run there")
            )
        )
        // Two runs of the same length: the earlier one in the shorter sample.
        assertEquals(
            "aaaa",
            RegexBuilder.longestCommonSubstring(listOf("xaaaayzzzzq", "aaaa zzzz"))
        )
        assertNull(RegexBuilder.longestCommonSubstring(listOf("abc", "abd")))
    }

    @Test
    fun `long machine headers do not take for ever to compare`() {
        // Two DKIM-sized values sharing a run near the front. Counting lengths
        // down and searching for every candidate took minutes on input this
        // size, which is how the rule wizard came to sit on its spinner.
        val shared = "v=1; a=rsa-sha256; c=relaxed/relaxed; d=mail.example; s=selector2024;"
        fun noise(seed: Long, length: Int): String {
            val random = java.util.Random(seed)
            return (1..length).joinToString("") { random.nextInt(16).toString(16) }
        }
        val began = System.currentTimeMillis()
        val found = RegexBuilder.longestCommonSubstring(
            listOf(
                noise(3, 100) + shared + noise(7, 4000),
                noise(11, 100) + shared + noise(13, 4000)
            )
        )
        val took = System.currentTimeMillis() - began
        assertNotNull(found)
        // Neighbouring noise can happen to agree on a character or two, so
        // what matters is that the shared run is in there.
        assertTrue("shared run not found, got $found", found!!.contains(shared))
        assertTrue("took ${took}ms", took < 2000)
    }
}
