package de.uwumail.ui.mail.gravity

import android.graphics.Paint
import android.graphics.Typeface
import org.json.JSONArray

/**
 * One character as it is actually drawn on screen: where it sits, and the paint
 * that reproduces it exactly.
 *
 * The point of measuring rather than re-typesetting is that the first frame of
 * the fall has to be indistinguishable from the message sitting still.
 */
data class GravityGlyph(
    val char: Char,
    /** Device pixels, relative to the falling area. */
    val x: Float,
    val y: Float,
    val size: Float,
    val paint: Paint
)

/**
 * Turns the measurements taken inside the page into glyphs.
 *
 * The page reports CSS pixels; [scale] converts those to device pixels, and
 * [offsetX]/[offsetY] place the page inside the area the letters fall in.
 */
object GlyphReader {

    /** Reads the JSON the measuring script returns. */
    fun parse(
        json: String,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        limit: Int
    ): List<GravityGlyph> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val paints = HashMap<Int, Paint>()
        val glyphs = ArrayList<GravityGlyph>(minOf(array.length(), limit))

        for (i in 0 until array.length()) {
            if (glyphs.size >= limit) break
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

            // A square around the character, centred where the character was.
            val box = maxOf(width, height)
            glyphs += GravityGlyph(
                char = character,
                x = offsetX + left + (width - box) * 0.5f,
                y = offsetY + top + (height - box) * 0.5f,
                size = box,
                paint = paint
            )
        }
        return glyphs
    }

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
