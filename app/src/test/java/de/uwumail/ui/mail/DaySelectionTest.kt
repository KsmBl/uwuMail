package de.uwumail.ui.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Taking a whole day at once from its heading.
 *
 * A day is the grouping the list already draws, so the line naming it is the
 * obvious handle for all of it — and tapping it again has to give exactly that
 * day back, without disturbing anything picked out elsewhere.
 */
class DaySelectionTest {

    private fun toggled(current: Set<Long>, day: List<Long>) =
        MailViewModel.withDayToggled(current, day)

    private val monday = listOf(1L, 2L, 3L)
    private val tuesday = listOf(4L, 5L)

    @Test
    fun `tapping an untouched day takes all of it`() {
        assertEquals(setOf(1L, 2L, 3L), toggled(emptySet(), monday))
    }

    @Test
    fun `tapping a day already taken gives it back`() {
        assertEquals(emptySet<Long>(), toggled(monday.toSet(), monday))
    }

    @Test
    fun `a day half taken is finished rather than undone`() {
        // The tap follows a few picked out by hand; it should complete them.
        assertEquals(setOf(1L, 2L, 3L), toggled(setOf(2L), monday))
    }

    @Test
    fun `taking a day leaves another day alone`() {
        assertEquals(setOf(4L, 5L, 1L, 2L, 3L), toggled(tuesday.toSet(), monday))
    }

    @Test
    fun `giving a day back leaves another day alone`() {
        val both = (monday + tuesday).toSet()

        assertEquals(tuesday.toSet(), toggled(both, monday))
    }

    @Test
    fun `giving a day back leaves a stray from elsewhere alone`() {
        assertEquals(setOf(99L), toggled(monday.toSet() + 99L, monday))
    }

    @Test
    fun `taking the same day twice is where it started`() {
        val once = toggled(emptySet(), monday)

        assertEquals(emptySet<Long>(), toggled(once, monday))
    }

    @Test
    fun `a day of one message toggles like any other`() {
        assertEquals(setOf(7L), toggled(emptySet(), listOf(7L)))
        assertEquals(emptySet<Long>(), toggled(setOf(7L), listOf(7L)))
    }

    @Test
    fun `a day with no mail in it changes nothing`() {
        assertEquals(setOf(1L), toggled(setOf(1L), emptyList()))
        assertTrue(toggled(emptySet(), emptyList()).isEmpty())
    }

    @Test
    fun `taking a day twice over does not double anything`() {
        assertEquals(3, toggled(setOf(1L, 2L), monday).size)
    }
}
