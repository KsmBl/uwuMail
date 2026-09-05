package de.uwumail.ui.mail.gravity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GravityWorldTest {

    private val width = 1080f
    private val height = 1920f
    private val letter = 30f

    private fun world(capacity: Int = 2000) = GravityWorld(width, height, capacity)

    /** One sixtieth of a second, the frame the simulation is built for. */
    private fun GravityWorld.run(frames: Int) = repeat(frames) { step(1f / 60f) }

    @Test
    fun `a letter falls`() {
        val world = world()
        world.add('a', 100f, 0f, letter)
        world.run(10)
        assertTrue("expected it to have fallen", world.y[0] > 0f)
    }

    @Test
    fun `a letter comes to rest on the floor`() {
        val world = world()
        world.add('a', 100f, 0f, letter)
        world.run(300)
        assertEquals(height - letter, world.y[0], 1f)
    }

    @Test
    fun `a letter never leaves the view`() {
        val world = world()
        // Thrown hard at the wall and at the floor.
        world.add('a', 100f, 100f, letter, vx = -9000f, vy = 9000f)
        repeat(600) {
            world.step(1f / 60f)
            assertTrue("left the view on the left", world.x[0] >= -0.5f)
            assertTrue("left the view on the right", world.x[0] + letter <= width + 0.5f)
            assertTrue("fell through the floor", world.y[0] + letter <= height + 0.5f)
        }
    }

    @Test
    fun `letters stack instead of sharing a spot`() {
        val world = world()
        repeat(20) { i -> world.add('x', 500f, -i * letter * 1.5f, letter) }
        world.run(600)

        // The column is 20 letters tall, so the top one is well above the floor.
        val highest = (0 until world.count).minOf { world.y[it] }
        assertTrue(
            "expected a stack, the top letter was at $highest",
            highest < height - letter * 8
        )
    }

    @Test
    fun `a settled pile has no letters inside one another`() {
        val world = world()
        val random = Random(7)
        repeat(400) {
            world.add(
                'm',
                random.nextFloat() * (width - letter),
                -random.nextFloat() * 2000f,
                letter
            )
        }
        world.run(900)

        // Position-based solving leaves a little slack; a letter's worth of
        // overlap would be letters visibly drawn on top of each other.
        assertTrue(
            "letters overlapped by ${world.worstOverlap()}px",
            world.worstOverlap() < letter * 0.5f
        )
    }

    @Test
    fun `everything ends up inside the view`() {
        val world = world()
        val random = Random(11)
        repeat(400) {
            world.add('w', random.nextFloat() * width, -random.nextFloat() * 1500f, letter)
        }
        world.run(900)

        for (i in 0 until world.count) {
            assertTrue(world.x[i] >= -0.5f && world.x[i] + letter <= width + 0.5f)
            assertTrue(world.y[i] + letter <= height + 0.5f)
        }
    }

    @Test
    fun `the simulation settles so the frame loop can stop`() {
        val world = world()
        repeat(120) { i ->
            world.add('e', 40f + (i % 30) * letter, -(i / 30) * letter, letter)
        }
        assertFalse(world.settled)
        world.run(1200)
        assertTrue("the pile never came to rest", world.settled)
    }

    @Test
    fun `a tilt stays small enough to keep the glyph in its box`() {
        val world = world()
        val random = Random(3)
        repeat(200) {
            world.add('l', random.nextFloat() * width, -random.nextFloat() * 1000f, letter)
        }
        world.run(600)
        for (i in 0 until world.count) {
            assertTrue("tilt was ${world.angle[i]}", kotlin.math.abs(world.angle[i]) <= 0.46f)
        }
    }

    @Test
    fun `an empty world is not reported as settled`() {
        assertFalse(world().settled)
    }

    @Test
    fun `letters beyond the capacity are dropped rather than overrunning`() {
        val world = world(capacity = 3)
        repeat(10) { world.add('a', 10f, 10f, letter) }
        assertEquals(3, world.count)
    }
}
