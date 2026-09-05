package de.uwumail.ui.mail.gravity

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import org.json.JSONArray

/**
 * One thing that falls: a character, or a picture.
 *
 * Both arrive measured from whatever drew them, so the first frame of the fall
 * is the message exactly as it was sitting. A letter is square; a picture is
 * whatever shape it was on the page.
 */
data class FallingPiece(
    /** Device pixels, relative to the falling area. */
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val char: Char = ' ',
    /** Set for a letter: the paint that reproduces it exactly. */
    val paint: Paint? = null,
    /** Set for a picture: the pixels lifted off the page. */
    val bitmap: Bitmap? = null
)

/** Turns the measurements taken inside the page into things that can fall. */
object GlyphReader {

    /** Stops a full stop or a thin space becoming a degenerate body. */
    private const val MIN_BOX = 3f

    /**
     * Reads the character measurements. The page reports CSS pixels; [scale]
     * converts those to device pixels, and [offsetX]/[offsetY] place the page
     * inside the area things fall in.
     */
    fun parseGlyphs(
        array: JSONArray,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        limit: Int
    ): List<FallingPiece> {
        val paints = HashMap<Int, Paint>()
        val pieces = ArrayList<FallingPiece>(minOf(array.length(), limit))

        for (i in 0 until array.length()) {
            if (pieces.size >= limit) break
            val entry = array.optJSONArray(i) ?: continue
            val text = entry.optString(0)
            if (text.isEmpty()) continue
            val character = text[0]
            // Half of a surrogate pair is not a letter and draws as a box.
            if (character.isSurrogate()) continue

            val left = entry.optDouble(1, 0.0).toFloat() * scale
            val top = entry.optDouble(2, 0.0).toFloat() * scale
            val width = entry.optDouble(3, 0.0).toFloat() * scale
            val height = entry.optDouble(4, 0.0).toFloat() * scale
            if (width <= 0f || height <= 0f) continue

            val colour = parseColour(entry.optString(5))
            val fontSize = entry.optDouble(6, 0.0).toFloat() * scale
            if (fontSize <= 0f) continue
            val family = entry.optString(7)
            val weight = entry.optInt(8, 400)
            val italic = entry.optInt(9, 0) == 1

            val paint = paints.getOrPut(key(colour, fontSize, family, weight, italic)) {
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = colour
                    textSize = fontSize
                    textAlign = Paint.Align.CENTER
                    typeface = typefaceFor(family, weight, italic)
                }
            }

            // The square is the character's own width, so an i is a small box
            // and an M a large one, and centred on where the character was so
            // that nothing moves on the first frame.
            val box = width.coerceAtLeast(MIN_BOX)
            pieces += FallingPiece(
                x = offsetX + left + (width - box) * 0.5f,
                y = offsetY + top + (height - box) * 0.5f,
                width = box,
                height = box,
                char = character,
                paint = paint
            )
        }
        return pieces
    }

    /** The rectangles the page reported for its pictures, in device pixels. */
    fun parseImageRects(array: JSONArray, scale: Float): List<FloatArray> =
        (0 until array.length()).mapNotNull { i ->
            val entry = array.optJSONArray(i) ?: return@mapNotNull null
            val left = entry.optDouble(0, 0.0).toFloat() * scale
            val top = entry.optDouble(1, 0.0).toFloat() * scale
            val width = entry.optDouble(2, 0.0).toFloat() * scale
            val height = entry.optDouble(3, 0.0).toFloat() * scale
            if (width < MIN_IMAGE || height < MIN_IMAGE) null
            else floatArrayOf(left, top, width, height)
        }

    /** Below this a picture is a spacer or a rule, and falling it looks like a glitch. */
    const val MIN_IMAGE = 8f

    private fun key(colour: Int, size: Float, family: String, weight: Int, italic: Boolean) =
        colour * 31 + size.toInt() * 131 + family.hashCode() * 17 + weight * 7 + if (italic) 1 else 0

    /** `rgb(r, g, b)` and `rgba(r, g, b, a)`, which is all a computed style returns. */
    fun parseColour(value: String): Int {
        val numbers = Regex("""[\d.]+""").findAll(value).map { it.value }.toList()
        if (numbers.size < 3) return android.graphics.Color.BLACK
        val red = numbers[0].toFloat().toInt().coerceIn(0, 255)
        val green = numbers[1].toFloat().toInt().coerceIn(0, 255)
        val blue = numbers[2].toFloat().toInt().coerceIn(0, 255)
        val alpha = if (numbers.size >= 4) {
            (numbers[3].toFloat() * 255f).toInt().coerceIn(0, 255)
        } else 255
        return android.graphics.Color.argb(alpha, red, green, blue)
    }

    private fun typefaceFor(family: String, weight: Int, italic: Boolean): Typeface {
        val lower = family.lowercase()
        val base = when {
            lower.contains("mono") || lower.contains("courier") -> Typeface.MONOSPACE
            lower.contains("serif") && !lower.contains("sans") -> Typeface.SERIF
            else -> Typeface.SANS_SERIF
        }
        val style = when {
            weight >= 600 && italic -> Typeface.BOLD_ITALIC
            weight >= 600 -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(base, style)
    }
}
