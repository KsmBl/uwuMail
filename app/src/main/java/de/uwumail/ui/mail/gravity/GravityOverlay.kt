package de.uwumail.ui.mail.gravity

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay

/**
 * Draws [glyphs] where they already are, then lets go of them.
 *
 * The glyphs arrive measured from whatever drew them, so the first frame is the
 * message exactly as it was sitting — same faces, sizes, colours and positions.
 * Everything that is not a letter stays where it is underneath, which is why
 * the pictures in a message do not go anywhere.
 */
@Composable
fun GravityOverlay(
    glyphs: List<GravityGlyph>,
    modifier: Modifier = Modifier
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    // Bumped once per simulated frame, and read only inside the draw lambda, so
    // a frame costs a redraw rather than a recomposition of the tree.
    var tick by remember { mutableIntStateOf(0) }

    // Glyph positions are in root coordinates; this view may not start there.
    val placed = remember(glyphs, origin) {
        glyphs.map { it.copy(x = it.x - origin.x, y = it.y - origin.y) }
    }

    val world = remember(placed, size) {
        if (size.width == 0 || size.height == 0 || placed.isEmpty()) null
        else GravityWorld(size.width.toFloat(), size.height.toFloat(), placed.size).apply {
            placed.forEach { add(it.char, it.x, it.y, it.size) }
        }
    }

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

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .onGloballyPositioned { origin = it.positionInRoot() }
    ) {
        val simulation = world ?: return@Canvas

        @Suppress("UNUSED_EXPRESSION")
        tick

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val glyph = CharArray(1)
            for (i in 0 until simulation.count) {
                val paint = placed[i].paint
                val half = simulation.size[i] * 0.5f
                val centreX = simulation.x[i] + half
                val centreY = simulation.y[i] + half
                // Sitting the glyph on its own baseline is what keeps the first
                // frame identical to the page it was lifted from.
                val baseline = centreY - (paint.ascent() + paint.descent()) / 2f
                glyph[0] = simulation.chars[i]
                val tilt = simulation.angle[i]
                if (tilt != 0f) {
                    native.save()
                    native.rotate(Math.toDegrees(tilt.toDouble()).toFloat(), centreX, centreY)
                    native.drawText(glyph, 0, 1, centreX, baseline, paint)
                    native.restore()
                } else {
                    native.drawText(glyph, 0, 1, centreX, baseline, paint)
                }
            }
        }
    }
}

private const val FIXED_STEP = 1f / 60f
private const val MAX_STEPS_PER_FRAME = 2
private const val MAX_OWED = 0.1f
private const val SETTLED_POLL_MILLIS = 100L

/**
 * Enough for a screenful of text. Past this the heap is deeper than the screen
 * anyway, and the frame budget is what pays for it.
 */
const val MAX_GRAVITY_LETTERS = 600
