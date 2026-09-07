package de.uwumail.ui.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picking mail out by dragging over it.
 *
 * A long press picked out one message and that was the end of the gesture, so
 * choosing twenty meant twenty separate presses. Holding and dragging now
 * reaches everything the finger passes, and resting against an edge carries the
 * list along under it.
 */
class DragSelectTest {

    /** Three rows of a hundred pixels each, starting at the top of the list. */
    private val rows = listOf(
        RowBounds(id = 1, top = 0, bottom = 100),
        RowBounds(id = 2, top = 100, bottom = 200),
        RowBounds(id = 3, top = 200, bottom = 300)
    )

    // -------------------------------------------------- what is under the finger

    @Test
    fun `a finger in the middle of a row is on that row`() {
        assertEquals(2L, rowAt(rows, 150f))
    }

    @Test
    fun `the top edge of a row belongs to it`() {
        assertEquals(2L, rowAt(rows, 100f))
    }

    @Test
    fun `the bottom edge of a row belongs to the next one`() {
        assertEquals(3L, rowAt(rows, 200f))
    }

    @Test
    fun `a finger over a day heading takes the nearest row`() {
        // The heading sits between two rows; the gap is nobody's row.
        val split = listOf(
            RowBounds(id = 1, top = 0, bottom = 100),
            RowBounds(id = 2, top = 160, bottom = 260)
        )

        assertEquals(1L, rowAt(split, 110f))
        assertEquals(2L, rowAt(split, 150f))
    }

    @Test
    fun `a finger dragged off the bottom stays on the last row`() {
        assertEquals(3L, rowAt(rows, 900f))
    }

    @Test
    fun `a finger dragged off the top stays on the first row`() {
        assertEquals(1L, rowAt(rows, -400f))
    }

    @Test
    fun `an empty list has nothing under the finger`() {
        assertNull(rowAt(emptyList(), 50f))
    }

    @Test
    fun `rows scrolled partly off the top still answer`() {
        val scrolled = listOf(
            RowBounds(id = 1, top = -60, bottom = 40),
            RowBounds(id = 2, top = 40, bottom = 140)
        )

        assertEquals(1L, rowAt(scrolled, 0f))
        assertEquals(2L, rowAt(scrolled, 100f))
    }

    // ------------------------------------------------------------- the range

    private val ids = listOf(10L, 20L, 30L, 40L, 50L)

    @Test
    fun `dragging down reaches everything between`() {
        assertEquals(listOf(20L, 30L, 40L), rangeBetween(ids, anchor = 20, through = 40))
    }

    @Test
    fun `dragging up reaches the same range`() {
        assertEquals(listOf(20L, 30L, 40L), rangeBetween(ids, anchor = 40, through = 20))
    }

    @Test
    fun `a drag that has not left its own row is just that row`() {
        assertEquals(listOf(30L), rangeBetween(ids, anchor = 30, through = 30))
    }

    @Test
    fun `the whole list can be reached end to end`() {
        assertEquals(ids, rangeBetween(ids, anchor = 10, through = 50))
    }

    @Test
    fun `a range across mail that has since gone is no range`() {
        assertTrue(rangeBetween(ids, anchor = 20, through = 999).isEmpty())
        assertTrue(rangeBetween(ids, anchor = 999, through = 20).isEmpty())
    }

    @Test
    fun `dragging back towards the anchor gives up what it reached`() {
        val reached = rangeBetween(ids, anchor = 10, through = 50)
        val givenBack = rangeBetween(ids, anchor = 10, through = 20)

        assertEquals(5, reached.size)
        assertEquals(listOf(10L, 20L), givenBack)
    }

    // -------------------------------------------------------- the auto-scroll

    private fun speed(y: Float) =
        autoScrollSpeed(y, viewportHeight = 1000f, edge = 100f, maxSpeed = 20f)

    @Test
    fun `a finger in the middle does not scroll the list`() {
        assertEquals(0f, speed(500f), 0.0001f)
    }

    @Test
    fun `a finger near the bottom scrolls down`() {
        assertTrue(speed(950f) > 0f)
    }

    @Test
    fun `a finger near the top scrolls up`() {
        assertTrue(speed(50f) < 0f)
    }

    @Test
    fun `deeper into the edge is faster`() {
        assertTrue(speed(990f) > speed(920f))
    }

    @Test
    fun `pressed against the very bottom runs at full speed`() {
        assertEquals(20f, speed(1000f), 0.0001f)
    }

    @Test
    fun `dragged beyond the edge does not go faster than full speed`() {
        assertEquals(20f, speed(5000f), 0.0001f)
        assertEquals(-20f, speed(-5000f), 0.0001f)
    }

    @Test
    fun `the very edge of the middle is still still`() {
        assertEquals(0f, speed(900f), 0.0001f)
        assertEquals(0f, speed(100f), 0.0001f)
    }

    @Test
    fun `a list with no height yet does not scroll`() {
        assertEquals(0f, autoScrollSpeed(50f, 0f, 100f, 20f), 0.0001f)
    }
}
