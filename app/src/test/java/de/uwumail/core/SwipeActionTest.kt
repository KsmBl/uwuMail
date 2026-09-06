package de.uwumail.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeActionTest {

    @Test
    fun `only the actions that empty the row carry it away`() {
        assertTrue(SwipeAction.ARCHIVE.carriesRowAway)
        assertTrue(SwipeAction.TRASH.carriesRowAway)
        assertTrue(SwipeAction.DELETE.carriesRowAway)
        // The row is still there afterwards, so the swipe has to spring back.
        assertFalse(SwipeAction.TOGGLE_READ.carriesRowAway)
        assertFalse(SwipeAction.TOGGLE_STAR.carriesRowAway)
        assertFalse(SwipeAction.NONE.carriesRowAway)
    }

    @Test
    fun `a move springs back, because the folder picker can still be dismissed`() {
        assertFalse(SwipeAction.MOVE.carriesRowAway)
    }

    @Test
    fun `only permanent deletion asks first`() {
        assertTrue(SwipeAction.DELETE.needsConfirmation)
        SwipeAction.entries.filter { it != SwipeAction.DELETE }
            .forEach { assertFalse(it.name, it.needsConfirmation) }
    }

    @Test
    fun `an unknown or missing setting reads as doing nothing`() {
        assertEquals(SwipeAction.NONE, SwipeAction.of(null))
        assertEquals(SwipeAction.NONE, SwipeAction.of(""))
        // A name from a future version must not crash the settings screen.
        assertEquals(SwipeAction.NONE, SwipeAction.of("SEND_TO_SPACE"))
    }

    @Test
    fun `a stored name round-trips`() {
        SwipeAction.entries.forEach { assertEquals(it, SwipeAction.of(it.name)) }
    }

    @Test
    fun `every action has its own label in the settings menu`() {
        // The labels are string resources now, so this checks they are set and
        // distinct rather than reading them.
        SwipeAction.entries.forEach { assertTrue(it.name, it.label != 0) }
        assertEquals(SwipeAction.entries.size, SwipeAction.entries.map { it.label }.toSet().size)
    }

    @Test
    fun `selecting is the only thing worth swiping while selecting`() {
        assertTrue(SwipeAction.SELECT.worksWhileSelecting)
        // Nothing is still nothing.
        assertTrue(SwipeAction.NONE.worksWhileSelecting)
        // Acting on one row while others sit selected and untouched does not.
        listOf(
            SwipeAction.ARCHIVE, SwipeAction.TRASH, SwipeAction.DELETE,
            SwipeAction.MOVE, SwipeAction.TOGGLE_READ, SwipeAction.TOGGLE_STAR
        ).forEach { assertFalse(it.name, it.worksWhileSelecting) }
    }

    @Test
    fun `selecting leaves the row where it is`() {
        assertFalse(SwipeAction.SELECT.carriesRowAway)
        assertFalse(SwipeAction.SELECT.needsConfirmation)
    }
}
