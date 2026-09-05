package de.uwumail.ui.mail.gravity

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * A message's text, one square per letter, falling into a heap.
 *
 * The text is set in the same face, size and colour it is read in, and every
 * letter starts exactly where it was written, so the message visibly comes
 * apart rather than being replaced by something else.
 *
 * Which way it falls follows the phone. Turn the phone over and the heap comes
 * apart and falls to what is now the bottom.
 */
@Composable
fun GravityBody(
    text: String,
    color: Color,
    fontSize: TextUnit,
    fontFamily: FontFamily?,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // Bumped once per simulated frame, and read only inside the draw lambda, so
    // a frame costs a redraw rather than a recomposition of the tree.
    var tick by remember { mutableIntStateOf(0) }

    val fontSizePx = with(density) { fontSize.toPx() }
    val paint = remember(color, fontSizePx, fontFamily) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = fontFamily.toTypeface()
            textAlign = Paint.Align.CENTER
            this.color = color.toArgb()
            textSize = fontSizePx
        }
    }

    val world = remember(text, canvasSize, paint) {
        if (canvasSize.width == 0 || canvasSize.height == 0) null
        else buildWorld(text, canvasSize, paint)
    }

    // Gravity follows the phone for as long as the letters are loose.
    val orientation by rememberDeviceOrientation(active = world != null)
    LaunchedEffect(world, orientation.down) {
        world?.setDown(orientation.down.x, orientation.down.y)
    }

    LaunchedEffect(world) {
        val simulation = world ?: return@LaunchedEffect
        var previous = 0L
        var owed = 0f
        while (true) {
            if (simulation.settled) {
                // Nothing is moving, so stop asking for frames. Turning the
                // phone wakes the heap and the loop picks up where it left off.
                previous = 0L
                owed = 0f
                delay(SETTLED_POLL_MILLIS)
                continue
            }
            withFrameNanos { now ->
                if (previous != 0L) {
                    // Always the same step, whatever the frame rate. How well a
                    // pile settles depends on the size of the step, so letting
                    // it follow the display would make the physics change with
                    // the phone; a slow frame runs the clock slow instead, and
                    // the backlog is capped so a stall cannot spiral.
                    owed = (owed + (now - previous) / 1_000_000_000f).coerceAtMost(MAX_OWED)
                    var steps = 0
                    while (owed >= FIXED_STEP && steps < MAX_STEPS_PER_FRAME) {
                        simulation.step(FIXED_STEP)
                        owed -= FIXED_STEP
                        steps++
                    }
                    if (steps > 0) tick++
                }
                previous = now
            }
        }
    }

    // Measured through onSizeChanged rather than from the draw scope: writing
    // state while drawing is what makes a frame invalidate itself forever.
    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
    ) {
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
 * Letters keep their real advances, so the paragraph looks like the message
 * did; the exception is the narrow ones, which are given at least a hitbox's
 * width. A square around a letter is wider than an `i` is, and without that
 * floor the two would start already inside one another and the first frame
 * would be a shove rather than a fall.
 *
 * A nudge of sideways speed keeps the collapse from looking mechanical:
 * without it every column falls dead straight.
 */
private fun buildWorld(text: String, canvas: IntSize, paint: Paint): GravityWorld {
    val world = GravityWorld(canvas.width.toFloat(), canvas.height.toFloat(), MAX_LETTERS)
    val box = paint.textSize * BOX_SCALE
    val lineHeight = (paint.descent() - paint.ascent()) * LINE_SPACING
    val right = canvas.width - MARGIN
    val bottom = canvas.height - box
    val random = Random(text.hashCode())
    val widths = FloatArray(1)

    var penX = MARGIN
    var penY = MARGIN
    val spaceWidth = paint.measureText(" ").coerceAtLeast(box * 0.5f)

    for (word in text.split(WHITESPACE)) {
        if (word.isEmpty()) continue
        val wordWidth = word.sumOf { character ->
            maxOf(paint.measureText(character.toString()), box).toDouble()
        }.toFloat()
        // Wrap before a word rather than inside it, unless it is longer than
        // the line, in which case it has to break somewhere.
        if (penX > MARGIN && penX + wordWidth > right) {
            penX = MARGIN
            penY += lineHeight
        }
        if (penY > bottom) break

        for (character in word) {
            // Half of a surrogate pair is not a letter and draws as a box.
            if (character.isSurrogate()) continue
            paint.getTextWidths(character.toString(), widths)
            val advance = maxOf(widths[0], box)
            if (penX + advance > right) {
                penX = MARGIN
                penY += lineHeight
                if (penY > bottom) return world
            }
            world.add(
                char = character,
                // Centred on the advance, so the letter sits where it was set.
                x = penX + (advance - box) * 0.5f,
                y = penY,
                size = box,
                vx = (random.nextFloat() - 0.5f) * SCATTER,
                vy = random.nextFloat() * SCATTER * 0.25f
            )
            if (world.count >= MAX_LETTERS) return world
            penX += advance
        }
        penX += spaceWidth
    }
    return world
}

/** The Compose font families that map onto a platform typeface. */
private fun FontFamily?.toTypeface(): android.graphics.Typeface = when (this) {
    FontFamily.Monospace -> android.graphics.Typeface.MONOSPACE
    FontFamily.Serif -> android.graphics.Typeface.SERIF
    FontFamily.Cursive -> android.graphics.Typeface.create("cursive", android.graphics.Typeface.NORMAL)
    else -> android.graphics.Typeface.SANS_SERIF
}

private val WHITESPACE = Regex("""\s+""")

/**
 * A letter's square, as a fraction of the font size. Close to the advance of
 * an average glyph, so the text is barely respaced by giving each letter one.
 */
private const val BOX_SCALE = 0.55f
private const val LINE_SPACING = 1.15f
private const val MARGIN = 12f
private const val SCATTER = 90f
private const val FIXED_STEP = 1f / 60f
private const val MAX_STEPS_PER_FRAME = 2
private const val MAX_OWED = 0.1f
private const val SETTLED_POLL_MILLIS = 100L

/**
 * Enough for a screenful of text. Past this the heap is deeper than the screen
 * anyway, and the frame budget is what pays for it.
 */
private const val MAX_LETTERS = 600
