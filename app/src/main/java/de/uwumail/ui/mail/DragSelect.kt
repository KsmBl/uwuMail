package de.uwumail.ui.mail

/** One message row's vertical extent inside the list's viewport, in pixels. */
data class RowBounds(val id: Long, val top: Int, val bottom: Int)

/**
 * The message the finger is over at [y].
 *
 * A finger dragging down a list spends part of its time over a day heading, in
 * the hairline between two rows, or past the last one entirely, and in none of
 * those cases has the user stopped pointing at something. So an exact hit is
 * preferred and the nearest row is taken otherwise: the selection follows the
 * finger continuously rather than freezing whenever it crosses a gap.
 */
fun rowAt(rows: List<RowBounds>, y: Float): Long? {
    if (rows.isEmpty()) return null
    rows.firstOrNull { y >= it.top && y < it.bottom }?.let { return it.id }
    return rows.minByOrNull { row ->
        when {
            y < row.top -> row.top - y
            else -> y - row.bottom
        }
    }?.id
}

/**
 * Every id from [anchor] through [through] inclusive, whichever way round the
 * two are, so dragging back up the list un-reaches what dragging down reached.
 *
 * Empty when either end is no longer in the list — mail can arrive or be
 * removed mid-drag, and a range across something that is gone is not a range.
 */
fun rangeBetween(ids: List<Long>, anchor: Long, through: Long): List<Long> {
    val from = ids.indexOf(anchor)
    val to = ids.indexOf(through)
    if (from < 0 || to < 0) return emptyList()
    return ids.subList(minOf(from, to), maxOf(from, to) + 1)
}

/**
 * How fast the list should scroll while a selection drag rests near an edge,
 * in pixels per frame — negative upwards, positive downwards, zero in the
 * middle.
 *
 * Proportional to how far into the edge the finger has gone, so easing towards
 * the bottom of the screen creeps and pressing right against it runs: a single
 * speed is either too slow to get anywhere or too fast to stop on the right
 * message.
 */
fun autoScrollSpeed(
    y: Float,
    viewportHeight: Float,
    edge: Float,
    maxSpeed: Float
): Float {
    if (viewportHeight <= 0f || edge <= 0f) return 0f
    val bottomEdge = viewportHeight - edge
    return when {
        y < edge -> -maxSpeed * ((edge - y) / edge).coerceIn(0f, 1f)
        y > bottomEdge -> maxSpeed * ((y - bottomEdge) / edge).coerceIn(0f, 1f)
        else -> 0f
    }
}
