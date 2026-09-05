package de.uwumail.ui.mail.gravity

import kotlin.math.abs
import kotlin.math.min

/**
 * A pile of falling letters.
 *
 * Every letter is an axis-aligned square that falls, bounces off the floor and
 * walls, and collides with every other letter. Positions are kept in parallel
 * arrays rather than in objects: this runs once per frame over a thousand
 * bodies, and an array of floats is what keeps that within a frame budget.
 *
 * Collisions are resolved by position rather than by accumulated impulses. It
 * is the cheaper half of a physics engine and the stable one — a pile a
 * thousand deep settles instead of jittering — and for letters tipping into a
 * heap it is indistinguishable from the real thing.
 *
 * No Android types are used here, so the whole simulation is testable.
 */
class GravityWorld(
    private var width: Float,
    private var height: Float,
    private val capacity: Int
) {

    val chars = CharArray(capacity)
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val size = FloatArray(capacity)
    val angle = FloatArray(capacity)

    private val vx = FloatArray(capacity)
    private val vy = FloatArray(capacity)
    private val spin = FloatArray(capacity)
    private val restFrames = IntArray(capacity)
    private val previousX = FloatArray(capacity)
    private val previousY = FloatArray(capacity)

    var count: Int = 0
        private set

    /**
     * Which way letters fall, as a unit vector in screen coordinates. Follows
     * the phone: turn it over and the heap comes apart and falls the other way.
     */
    private var downX = 0f
    private var downY = 1f

    /** True once every letter has come to rest, so the frame loop can stop. */
    val settled: Boolean get() = count > 0 && sleeping == count

    private var sleeping = 0

    // Uniform grid, rebuilt each step: with every body the same size, a grid is
    // an O(n) broad phase, where checking every pair would be O(n²).
    private var cellSize = 1f
    private var columns = 1
    private var rows = 1
    private var cellStart = IntArray(1)
    private var cellCursor = IntArray(1)
    private var cellItems = IntArray(capacity)

    /** [x] and [y] are the letter's top-left corner. */
    fun add(char: Char, x: Float, y: Float, size: Float, vx: Float = 0f, vy: Float = 0f) {
        if (count >= capacity) return
        val i = count++
        chars[i] = char
        this.x[i] = x
        this.y[i] = y
        this.size[i] = size
        this.vx[i] = vx
        this.vy[i] = vy
        angle[i] = 0f
        spin[i] = 0f
        restFrames[i] = 0
        previousX[i] = x
        previousY[i] = y
    }

    fun resize(width: Float, height: Float) {
        this.width = width
        this.height = height
    }

    /**
     * Points gravity somewhere new. A settled heap is woken by a real change of
     * direction, since otherwise turning the phone would leave it hanging on
     * what is no longer a floor.
     */
    fun setDown(x: Float, y: Float) {
        if (abs(x - downX) + abs(y - downY) < DIRECTION_EPSILON) return
        downX = x
        downY = y
        for (i in 0 until count) restFrames[i] = 0
    }

    /**
     * Advances the simulation by [dt] seconds.
     *
     * The frame is cut into substeps rather than solved once with more
     * iterations. For the same amount of work a substep converges far better,
     * because each one re-integrates and rebuilds contacts instead of grinding
     * away at a snapshot that is already stale — which is what decides whether
     * a deep pile settles or stays crushed into itself.
     */
    fun step(dt: Float) {
        if (count == 0) return
        val h = dt / SUBSTEPS
        repeat(SUBSTEPS) {
            integrate(h)
            buildGrid()
            repeat(SOLVER_ITERATIONS) {
                solveCollisions()
                solveBounds()
            }
        }
        updateSleep()
    }

    private fun integrate(dt: Float) {
        for (i in 0 until count) {
            if (restFrames[i] >= SLEEP_FRAMES) continue
            vx[i] += GRAVITY * downX * dt
            vy[i] += GRAVITY * downY * dt
            vx[i] *= AIR_DRAG
            vy[i] *= AIR_DRAG
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            angle[i] += spin[i] * dt
            spin[i] *= SPIN_DRAG
            // A letter is drawn inside its square, so the tilt has to stay small
            // enough that the drawing does not leave the box that collides.
            if (angle[i] > MAX_TILT) { angle[i] = MAX_TILT; spin[i] = 0f }
            if (angle[i] < -MAX_TILT) { angle[i] = -MAX_TILT; spin[i] = 0f }
        }
    }

    private fun buildGrid() {
        var largest = 1f
        for (i in 0 until count) largest = maxOf(largest, size[i])
        cellSize = largest
        columns = ((width / cellSize).toInt() + 1).coerceAtLeast(1)
        rows = ((height / cellSize).toInt() + 3).coerceAtLeast(1)

        val cells = columns * rows
        if (cellStart.size < cells + 1) {
            // Grown, never reallocated per frame: this runs sixty times a second.
            cellStart = IntArray(cells + 1)
            cellCursor = IntArray(cells + 1)
        }
        java.util.Arrays.fill(cellStart, 0, cells + 1, 0)
        java.util.Arrays.fill(cellCursor, 0, cells, 0)

        for (i in 0 until count) cellStart[cellOf(i) + 1]++
        for (c in 0 until cells) cellStart[c + 1] += cellStart[c]

        for (i in 0 until count) {
            val cell = cellOf(i)
            cellItems[cellStart[cell] + cellCursor[cell]] = i
            cellCursor[cell]++
        }
    }

    private fun cellOf(i: Int): Int {
        val column = ((x[i] + size[i] * 0.5f) / cellSize).toInt().coerceIn(0, columns - 1)
        // Letters start above the top of the view, so the row is clamped rather
        // than assumed to be on screen.
        val row = ((y[i] + size[i] * 0.5f) / cellSize).toInt().coerceIn(0, rows - 1)
        return row * columns + column
    }

    /**
     * Rows are solved from the bottom up, because that is the direction
     * support travels: separating the floor row first gives the row above it
     * somewhere to be pushed to, where the other order squashes the pile flat
     * and takes many more passes to undo.
     */
    private fun solveCollisions() {
        for (row in rows - 1 downTo 0) {
            for (column in 0 until columns) {
                val cell = row * columns + column
                for (a in cellStart[cell] until cellStart[cell + 1]) {
                    val i = cellItems[a]
                    // Only the cell itself and the three after it in reading
                    // order, so each pair is visited once.
                    resolveAgainstCell(i, cell, a + 1)
                    if (column + 1 < columns) resolveAgainstCell(i, cell + 1, -1)
                    if (row + 1 < rows) {
                        val below = cell + columns
                        if (column > 0) resolveAgainstCell(i, below - 1, -1)
                        resolveAgainstCell(i, below, -1)
                        if (column + 1 < columns) resolveAgainstCell(i, below + 1, -1)
                    }
                }
            }
        }
    }

    private fun resolveAgainstCell(i: Int, cell: Int, from: Int) {
        val start = if (from >= 0) from else cellStart[cell]
        for (b in start until cellStart[cell + 1]) {
            val j = cellItems[b]
            if (j != i) resolvePair(i, j)
        }
    }

    /** Separates two overlapping squares along whichever axis they overlap least. */
    private fun resolvePair(i: Int, j: Int) {
        val halfI = size[i] * 0.5f
        val halfJ = size[j] * 0.5f
        val dx = (x[j] + halfJ) - (x[i] + halfI)
        val dy = (y[j] + halfJ) - (y[i] + halfI)
        // Letters in a settled heap rest a hair inside one another. Treating
        // that as a collision would have every letter shoving its neighbours
        // awake forever, so contact only counts past a slop.
        val overlapX = (halfI + halfJ) - abs(dx)
        if (overlapX <= SLOP) return
        val overlapY = (halfI + halfJ) - abs(dy)
        if (overlapY <= SLOP) return

        // Waking comes first: two letters that fell asleep still inside one
        // another would otherwise both be immovable, and stay overlapped for
        // good.
        if (overlapX > WAKE_SLOP && overlapY > WAKE_SLOP) {
            wake(i)
            wake(j)
        }

        // Both letters give ground equally. Pinning settled ones instead is
        // tempting and wrong: a letter that lands between two of them would be
        // wedged with nowhere to go and stay half inside both for good.
        val shareI = 0.5f
        val shareJ = 0.5f
        val movableI = 1f
        val movableJ = 1f

        if (overlapX < overlapY) {
            val correction = (overlapX - SLOP) * SEPARATION
            val sign = if (dx < 0f) 1f else -1f
            x[i] += correction * shareI * sign
            x[j] -= correction * shareJ * sign
            val relative = vx[j] - vx[i]
            if (relative * -sign < 0f) {
                val exchange = relative * (1f + RESTITUTION)
                vx[i] += exchange * shareI
                vx[j] -= exchange * shareJ
            }
            // A sideways shove is what makes a letter tip over.
            spin[i] -= sign * overlapX * TIP * movableI
            spin[j] += sign * overlapX * TIP * movableJ
        } else {
            val correction = (overlapY - SLOP) * SEPARATION
            val sign = if (dy < 0f) 1f else -1f
            y[i] += correction * shareI * sign
            y[j] -= correction * shareJ * sign
            val relative = vy[j] - vy[i]
            if (relative * -sign < 0f) {
                val exchange = relative * (1f + RESTITUTION)
                vy[i] += exchange * shareI
                vy[j] -= exchange * shareJ
            }
            // Landing on another letter scrubs off sideways speed, so a heap
            // holds together instead of sliding apart.
            vx[i] *= FRICTION
            vx[j] *= FRICTION
        }
    }

    /**
     * Keeps every letter inside the view.
     *
     * All four edges are solid, because down is wherever the phone says it is:
     * held over, the ceiling is the floor.
     *
     * The wake here uses the same slop as letter-on-letter contact. The row
     * against the wall is pressed a fraction of a pixel into it by the weight
     * behind it every single frame, and treating that as an impact would keep
     * the whole floor awake for good.
     */
    private fun solveBounds() {
        for (i in 0 until count) {
            val extent = size[i]
            if (x[i] < 0f) {
                if (-x[i] > WAKE_SLOP) wake(i)
                x[i] = 0f
                if (vx[i] < 0f) {
                    vx[i] = -vx[i] * RESTITUTION
                    vy[i] *= FRICTION
                }
            } else if (x[i] + extent > width) {
                if (x[i] + extent - width > WAKE_SLOP) wake(i)
                x[i] = width - extent
                if (vx[i] > 0f) {
                    vx[i] = -vx[i] * RESTITUTION
                    vy[i] *= FRICTION
                }
            }
            if (y[i] < 0f) {
                if (-y[i] > WAKE_SLOP) wake(i)
                y[i] = 0f
                if (vy[i] < 0f) {
                    vy[i] = -vy[i] * RESTITUTION
                    vx[i] *= FRICTION
                }
            } else if (y[i] + extent > height) {
                if (y[i] + extent - height > WAKE_SLOP) wake(i)
                y[i] = height - extent
                if (vy[i] > 0f) {
                    vy[i] = -vy[i] * RESTITUTION
                    vx[i] *= FRICTION
                    spin[i] *= FRICTION
                }
            }
        }
    }

    /**
     * Letters that have stopped moving are taken out of the integrator. A
     * settled pile is most of the simulation's life, so this is the difference
     * between a thousand letters costing a frame and costing nothing.
     *
     * Rest is judged by how far a letter actually travelled over the frame
     * rather than by its velocity: a letter pressed into a heap keeps being
     * given velocity by gravity and having it taken away again by its
     * neighbours, and never looks still by that measure even though it has not
     * moved a pixel in seconds.
     */
    private fun updateSleep() {
        sleeping = 0
        for (i in 0 until count) {
            val travelled = abs(x[i] - previousX[i]) + abs(y[i] - previousY[i])
            previousX[i] = x[i]
            previousY[i] = y[i]
            if (travelled < REST_DISTANCE) {
                if (restFrames[i] < SLEEP_FRAMES) restFrames[i]++
                if (restFrames[i] >= SLEEP_FRAMES) {
                    vx[i] = 0f
                    vy[i] = 0f
                    spin[i] = 0f
                    sleeping++
                }
            } else {
                restFrames[i] = 0
            }
        }
    }

    private fun wake(i: Int) {
        restFrames[i] = 0
    }

    /** Largest overlap between any two letters, in pixels. Used by the tests. */
    fun worstOverlap(): Float {
        var worst = 0f
        for (i in 0 until count) {
            for (j in i + 1 until count) {
                val overlapX = (size[i] + size[j]) * 0.5f -
                    abs((x[j] + size[j] * 0.5f) - (x[i] + size[i] * 0.5f))
                if (overlapX <= 0f) continue
                val overlapY = (size[i] + size[j]) * 0.5f -
                    abs((y[j] + size[j] * 0.5f) - (y[i] + size[i] * 0.5f))
                if (overlapY <= 0f) continue
                worst = maxOf(worst, min(overlapX, overlapY))
            }
        }
        return worst
    }

    companion object {
        /** Pixels per second squared: about 3g, which reads as "heavy" on a phone. */
        const val GRAVITY = 2800f
        private const val RESTITUTION = 0.18f
        private const val FRICTION = 0.86f
        private const val AIR_DRAG = 0.999f
        private const val SPIN_DRAG = 0.94f
        private const val SEPARATION = 1f
        /** Contact under this is a resting touch, not a collision. */
        private const val SLOP = 0.4f
        private const val WAKE_SLOP = 1.5f
        private const val TIP = 0.05f
        private const val MAX_TILT = 0.45f
        private const val SUBSTEPS = 8
        private const val SOLVER_ITERATIONS = 2
        /** Movement per frame below which a letter counts as still, in pixels. */
        private const val REST_DISTANCE = 0.25f
        /** Below this a direction change is sensor noise, not the phone turning. */
        private const val DIRECTION_EPSILON = 0.08f
        private const val SLEEP_FRAMES = 24
    }
}
