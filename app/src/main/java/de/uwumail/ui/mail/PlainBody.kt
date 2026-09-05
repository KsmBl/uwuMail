package de.uwumail.ui.mail

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import de.uwumail.ui.mail.gravity.GravityGlyph

/** Stops a full stop or a thin space becoming a degenerate body. */
private const val MIN_BOX = 3f

/**
 * A plain-text mail body.
 *
 * When [handOverGlyphs] is set it measures its own characters with the same
 * text engine that drew them and reports them in root coordinates, then draws
 * itself in nothing so that whoever asked for them can take over without the
 * text appearing to move.
 */
@Composable
fun PlainBody(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    handOverGlyphs: Boolean = false,
    glyphLimit: Int = 0,
    onGlyphs: (List<GravityGlyph>) -> Unit = {}
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val currentOnGlyphs by rememberUpdatedState(onGlyphs)

    var size by remember { mutableStateOf(IntSize.Zero) }
    var position by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(handOverGlyphs, text, size, position, color) {
        if (!handOverGlyphs || size.width == 0) {
            if (!handOverGlyphs) currentOnGlyphs(emptyList())
            return@LaunchedEffect
        }
        val layout = measurer.measure(
            text = text,
            style = style.copy(color = color),
            constraints = Constraints(maxWidth = size.width)
        )
        val fontSizePx = with(density) { style.fontSize.toPx() }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            textSize = fontSizePx
            textAlign = Paint.Align.CENTER
            typeface = Typeface.SANS_SERIF
        }

        val glyphs = ArrayList<GravityGlyph>(minOf(text.length, glyphLimit))
        for (offset in text.indices) {
            if (glyphs.size >= glyphLimit) break
            val character = text[offset]
            if (character.isWhitespace() || character.isSurrogate()) continue
            val box = runCatching { layout.getBoundingBox(offset) }.getOrNull() ?: continue
            if (box.width <= 0f || box.height <= 0f) continue
            // The square is the character's own width, so an i is a small box
            // and an M a large one.
            val side = box.width.coerceAtLeast(MIN_BOX)
            glyphs += GravityGlyph(
                char = character,
                x = position.x + box.left + (box.width - side) * 0.5f,
                y = position.y + box.top + (box.height - side) * 0.5f,
                size = side,
                paint = paint
            )
        }
        currentOnGlyphs(glyphs)
    }

    Text(
        text = text,
        style = style,
        // The letters are drawn by the overlay from here on; leaving the text
        // in place keeps the layout, and the height, exactly as it was.
        color = if (handOverGlyphs) Color.Transparent else color,
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .onSizeChanged { size = it }
            .onGloballyPositioned { position = it.positionInRoot() }
    )
}
