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
        repeat(20) { i -> world.add('x', 500f, i * letter * 1.5f, letter) }
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
                random.nextFloat() * (height - letter),
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
            world.add('w', random.nextFloat() * width, random.nextFloat() * height, letter)
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
            world.add('e', 40f + (i % 30) * letter, 40f + (i / 30) * letter, letter)
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
            world.add('l', random.nextFloat() * width, random.nextFloat() * height, letter)
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

    @Test
    fun `letters fall towards whichever way is down`() {
        val world = world()
        world.setDown(1f, 0f)
        world.add('a', width / 2, height / 2, letter)
        world.run(300)
        // The right-hand edge is the floor now.
        assertEquals(width - letter, world.x[0], 1f)
        assertEquals(height / 2, world.y[0], 2f)
    }

    @Test
    fun `turning the phone over sends the heap to the other end`() {
        val world = world()
        repeat(30) { i -> world.add('u', 100f + i * letter, 200f, letter) }
        world.run(400)
        assertTrue("expected them at the bottom", (0 until world.count).all {
            world.y[it] > height - letter * 3
        })

        world.setDown(0f, -1f)
        world.run(600)
        assertTrue("expected them at the top", (0 until world.count).all {
            world.y[it] < letter * 3
        })
    }

    @Test
    fun `every edge holds letters in`() {
        for (direction in listOf(0f to 1f, 0f to -1f, 1f to 0f, -1f to 0f)) {
            val world = world()
            world.setDown(direction.first, direction.second)
            val random = Random(5)
            repeat(100) {
                world.add(
                    'o',
                    random.nextFloat() * (width - letter),
                    random.nextFloat() * (height - letter),
                    letter
                )
            }
            world.run(600)
            for (i in 0 until world.count) {
                assertTrue("escaped with down=$direction", world.x[i] >= -0.5f)
                assertTrue("escaped with down=$direction", world.x[i] + letter <= width + 0.5f)
                assertTrue("escaped with down=$direction", world.y[i] >= -0.5f)
                assertTrue("escaped with down=$direction", world.y[i] + letter <= height + 0.5f)
            }
        }
    }

    @Test
    fun `sensor noise does not wake a settled heap`() {
        val world = world()
        repeat(40) { i -> world.add('n', 100f + (i % 20) * letter, 200f, letter) }
        world.run(600)
        assertTrue(world.settled)
        world.setDown(0.02f, 0.999f)
        world.step(1f / 60f)
        assertTrue("a hair of drift should not restart it", world.settled)
    }

    @Test
    fun `letters of different widths still collide correctly`() {
        // An i is a small box and an M a large one, so the broad phase has to
        // cope with a range of sizes rather than a uniform grid of one.
        val world = world()
        val random = Random(21)
        repeat(300) {
            val side = 8f + random.nextFloat() * 34f
            world.add(
                'x',
                random.nextFloat() * (width - side),
                random.nextFloat() * (height - side),
                side
            )
        }
        world.run(900)

        for (i in 0 until world.count) {
            assertTrue(world.x[i] >= -0.5f && world.x[i] + world.width[i] <= width + 0.5f)
            assertTrue(world.y[i] >= -0.5f && world.y[i] + world.height[i] <= height + 0.5f)
        }
        // Letters in a heap do overlap, and a heap of text should look like
        // one. What must not happen is a small letter disappearing inside a
        // large one, which is what an unweighted split used to produce.
        assertTrue(
            "a letter was buried ${world.worstRelativeOverlap()} deep in another",
            world.worstRelativeOverlap() < 1f
        )
    }

    @Test
    fun `a new direction unsettles the heap straight away`() {
        // Callers stop stepping a settled world, so this has to be true before
        // the next step rather than because of it.
        val world = world()
        repeat(40) { i -> world.add('s', 100f + (i % 20) * letter, 200f, letter) }
        world.run(600)
        assertTrue(world.settled)

        world.setDown(1f, 0f)
        assertFalse("turning the phone must wake the heap at once", world.settled)
    }

    @Test
    fun `a wide picture falls flat and holds letters up`() {
        val world = world()
        // A banner: far wider than it is tall, which a square body cannot model.
        world.add(' ', 100f, 900f, width = 700f, height = 90f)
        repeat(30) { i -> world.add('o', 150f + (i % 15) * letter, 300f + (i / 15) * letter, letter) }
        world.run(900)

        val picture = 0
        assertEquals(700f, world.width[picture], 0.01f)
        assertEquals(90f, world.height[picture], 0.01f)
        assertEquals(height - 90f, world.y[picture], 1.5f)

        // The letters landed on top of it rather than through it.
        val pictureTop = world.y[picture]
        for (i in 1 until world.count) {
            assertTrue(
                "a letter at ${world.y[i]} went through the picture at $pictureTop",
                world.y[i] + world.height[i] <= pictureTop + letter
            )
        }
    }

    @Test
    fun `a tall picture is held by the side walls, not by its width`() {
        val world = world()
        world.add(' ', 0f, 100f, width = 60f, height = 600f)
        world.setDown(-1f, 0f)
        world.run(600)
        // Pushed to the left edge, still its own shape.
        assertEquals(0f, world.x[0], 1f)
        assertEquals(60f, world.width[0], 0.01f)
        assertEquals(600f, world.height[0], 0.01f)
    }
}
