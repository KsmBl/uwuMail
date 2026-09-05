package de.uwumail.ui.mail.gravity

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A message's text, one square per letter, falling into a heap.
 *
 * Every letter starts exactly where it was written and is then let go, so the
 * text visibly comes apart rather than being replaced by something else. The
 * layout is a uniform grid of squares because that is what the letters collide
 * as: a square around a letter is wider than the letter's own advance, so the
 * text is set slightly wide and every hitbox is exactly what is drawn.
 */
@Composable
fun GravityBody(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // Bumped once per simulated frame. Read inside the draw lambda only, so a
    // frame costs a redraw and not a recomposition of the tree.
    var tick by remember { mutableIntStateOf(0) }

    val cellPx = with(density) { CELL.toPx() }
    val paint = remember(color, cellPx) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
            this.color = color.toArgb()
            // Sized so the glyph fills its square without spilling out of it.
            textSize = cellPx * GLYPH_SCALE
        }
    }

    val world = remember(text, canvasSize, cellPx) {
        if (canvasSize.width == 0 || canvasSize.height == 0) null
        else buildWorld(text, canvasSize, cellPx)
    }

    LaunchedEffect(world) {
        val simulation = world ?: return@LaunchedEffect
        var previous = 0L
        while (true) {
            var stop = false
            androidx.compose.runtime.withFrameNanos { now ->
                if (previous != 0L) {
                    // A long frame is clamped rather than simulated in one go:
                    // a letter must never tunnel through the floor because the
                    // app was busy elsewhere.
                    val dt = ((now - previous) / 1_000_000_000f).coerceAtMost(MAX_STEP)
                    simulation.step(dt)
                    tick++
                }
                previous = now
                stop = simulation.settled
            }
            // Once the pile has come to rest there is nothing left to draw.
            if (stop) break
        }
    }

    Canvas(modifier.fillMaxSize()) {
        val current = size
        val measured = IntSize(current.width.roundToInt(), current.height.roundToInt())
        if (measured != canvasSize) canvasSize = measured
        val simulation = world ?: return@Canvas

        @Suppress("UNUSED_EXPRESSION")
        tick

        val baselineOffset = -(paint.ascent() + paint.descent()) / 2f
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val glyph = CharArray(1)
            for (i in 0 until simulation.count) {
                val half = simulation.size[i] * 0.5f
                val centreX = simulation.x[i] + half
                val centreY = simulation.y[i] + half
                glyph[0] = simulation.chars[i]
                val tilt = simulation.angle[i]
                if (tilt != 0f) {
                    native.save()
                    native.rotate(Math.toDegrees(tilt.toDouble()).toFloat(), centreX, centreY)
                    native.drawText(glyph, 0, 1, centreX, centreY + baselineOffset, paint)
                    native.restore()
                } else {
                    native.drawText(glyph, 0, 1, centreX, centreY + baselineOffset, paint)
                }
            }
        }
    }
}

/**
 * Lays the text out as it would be read, then hands it to the simulation.
 *
 * Words are kept whole across line breaks, so the text is legible for the
 * moment before it falls. A nudge of sideways speed keeps the collapse from
 * looking like a machine: without it every column falls dead straight.
 */
private fun buildWorld(text: String, canvas: IntSize, cell: Float): GravityWorld {
    val world = GravityWorld(canvas.width.toFloat(), canvas.height.toFloat(), MAX_LETTERS)
    val columns = ((canvas.width - MARGIN * 2) / cell).toInt().coerceAtLeast(1)
    val random = Random(text.hashCode())

    var column = 0
    var row = 0
    for (word in text.split(WHITESPACE)) {
        if (word.isEmpty()) continue
        // Wrap before a word rather than inside it, unless it is longer than
        // the line, in which case it has to break somewhere.
        if (column + word.length > columns && word.length <= columns) {
            column = 0
            row++
        }
        for (character in word) {
            if (column >= columns) {
                column = 0
                row++
            }
            world.add(
                char = character,
                x = MARGIN + column * cell,
                y = MARGIN + row * cell,
                size = cell,
                vx = (random.nextFloat() - 0.5f) * SCATTER,
                vy = random.nextFloat() * SCATTER * 0.25f
            )
            if (world.count >= MAX_LETTERS) return world
            column++
        }
        column++
    }
    return world
}

private val WHITESPACE = Regex("""\s+""")

/** The side of a letter's square. Small enough to read, big enough to see fall. */
private val CELL = 13.dp
private const val GLYPH_SCALE = 1.15f
private const val MARGIN = 12f
private const val SCATTER = 90f
private const val MAX_STEP = 1f / 30f

/**
 * Enough for a screen and a half of text. Past this the heap is taller than
 * the screen anyway, and the frame budget is what pays for it.
 */
private const val MAX_LETTERS = 600
