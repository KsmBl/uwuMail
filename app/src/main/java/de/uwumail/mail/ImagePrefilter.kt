package de.uwumail.mail

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** What [ImagePrefilter] made of a body. */
data class PrefilteredBody(
    val html: String,
    /** Images taken out because they were too small to be anything but a beacon. */
    val tinyRemoved: Int,
    /** Images taken out because the page never intended to show them. */
    val hiddenRemoved: Int,
    /** Remote images the body would still fetch if it were allowed to. */
    val remoteRemaining: Int
) {
    val removed: Int get() = tinyRemoved + hiddenRemoved
}

/**
 * Takes tracking pixels out of a body before it is ever rendered.
 *
 * Measuring an image after downloading it is too late — the download is the
 * thing the sender is watching for. Almost every tracking pixel says what it is
 * in the markup, as a `width="1"`, a `display:none` or a `height:0`, so the
 * decision can be made from the document alone and the request never made.
 *
 * The stylesheet is applied by hand rather than by a browser, so this is a
 * reading of the CSS and not the CSS engine: declarations are collected in
 * document order with inline styles last, and specificity is not weighed.
 * Everything it cannot read it leaves alone, which is the safe direction — an
 * image wrongly kept is shown, an image wrongly removed is gone.
 */
object ImagePrefilter {

    fun strip(html: String, policy: RemoteImagePolicy): PrefilteredBody {
        val document = runCatching { Jsoup.parse(html) }.getOrNull()
            ?: return PrefilteredBody(html, 0, 0, 0)

        val declared = collectStyles(document)
        var tiny = 0
        var hidden = 0

        for (image in document.select("img")) {
            val style = styleOf(image, declared)
            if (isHidden(image, declared)) {
                image.remove()
                hidden++
                continue
            }
            if (!policy.filterTiny) continue
            val width = lengthOf(image.attr("width")) ?: pixelsOf(style["width"])
            val height = lengthOf(image.attr("height")) ?: pixelsOf(style["height"])
            if ((width != null && width < policy.minWidth) ||
                (height != null && height < policy.minHeight)
            ) {
                image.remove()
                tiny++
            }
        }

        val remaining = document.select("img[src]").count { element ->
            val source = element.attr("src")
            source.startsWith("http://", true) || source.startsWith("https://", true)
        }
        return PrefilteredBody(document.outerHtml(), tiny, hidden, remaining)
    }

    /**
     * An element the page does not draw, or draws at nothing. Ancestors count:
     * the usual way to hide a beacon is a `display:none` wrapper around it.
     */
    private fun isHidden(image: Element, declared: Map<Element, Map<String, String>>): Boolean {
        var element: Element? = image
        while (element != null) {
            val style = styleOf(element, declared)
            if (style["display"] == "none") return true
            if (style["visibility"] == "hidden" || style["visibility"] == "collapse") return true
            style["opacity"]?.toFloatOrNull()?.let { if (it <= 0f) return true }
            if (element === image) {
                if (pixelsOf(style["width"]) == 0f || pixelsOf(style["height"]) == 0f) return true
            }
            element = element.parent()
        }
        // A width or height attribute of zero says the same thing.
        return lengthOf(image.attr("width")) == 0f || lengthOf(image.attr("height")) == 0f
    }

    private fun styleOf(
        element: Element,
        declared: Map<Element, Map<String, String>>
    ): Map<String, String> {
        val fromSheet = declared[element].orEmpty()
        val inline = parseDeclarations(element.attr("style"))
        if (fromSheet.isEmpty()) return inline
        if (inline.isEmpty()) return fromSheet
        return fromSheet + inline
    }

    /**
     * Applies the document's own `<style>` blocks to the elements they select.
     *
     * `@media` and `@supports` blocks are skipped: whether they apply depends on
     * the viewport, and a body hidden only in some other viewport is one this
     * one should still show.
     */
    private fun collectStyles(document: Document): Map<Element, Map<String, String>> {
        val result = HashMap<Element, MutableMap<String, String>>()
        for (sheet in document.select("style")) {
            val css = stripAtRules(sheet.data())
            for ((selector, declarations) in parseRules(css)) {
                if (declarations.isEmpty()) continue
                for (part in selector.split(',')) {
                    val trimmed = part.trim()
                    if (trimmed.isEmpty()) continue
                    val matched = runCatching { document.select(trimmed) }.getOrNull() ?: continue
                    for (element in matched) {
                        result.getOrPut(element) { HashMap() }.putAll(declarations)
                    }
                }
            }
        }
        return result
    }

    /** Drops `@media { ... }` and friends, braces and all. */
    private fun stripAtRules(css: String): String {
        val out = StringBuilder(css.length)
        var index = 0
        while (index < css.length) {
            val at = css.indexOf('@', index)
            if (at < 0) {
                out.append(css, index, css.length)
                break
            }
            out.append(css, index, at)
            val open = css.indexOf('{', at)
            if (open < 0) break
            var depth = 0
            var scan = open
            while (scan < css.length) {
                if (css[scan] == '{') depth++
                if (css[scan] == '}') {
                    depth--
                    if (depth == 0) break
                }
                scan++
            }
            index = if (scan >= css.length) css.length else scan + 1
        }
        return out.toString()
    }

    private fun parseRules(css: String): List<Pair<String, Map<String, String>>> =
        css.split('}').mapNotNull { chunk ->
            val brace = chunk.indexOf('{')
            if (brace < 0) return@mapNotNull null
            val selector = chunk.substring(0, brace).trim()
            if (selector.isEmpty()) return@mapNotNull null
            selector to parseDeclarations(chunk.substring(brace + 1))
        }

    private fun parseDeclarations(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val result = HashMap<String, String>()
        for (declaration in text.split(';')) {
            val colon = declaration.indexOf(':')
            if (colon <= 0) continue
            val name = declaration.substring(0, colon).trim().lowercase()
            val value = declaration.substring(colon + 1)
                .replace("!important", "", ignoreCase = true)
                .trim()
                .lowercase()
            if (name.isNotEmpty() && value.isNotEmpty()) result[name] = value
        }
        return result
    }

    /** A `width`/`height` attribute, which is pixels unless it is a percentage. */
    private fun lengthOf(value: String): Float? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.endsWith("%")) return null
        return trimmed.removeSuffix("px").trim().toFloatOrNull()
    }

    /** A CSS length, in pixels. Anything in other units is left unknown. */
    private fun pixelsOf(value: String?): Float? {
        val trimmed = value?.trim() ?: return null
        if (trimmed == "0") return 0f
        if (!trimmed.endsWith("px")) return null
        return trimmed.removeSuffix("px").trim().toFloatOrNull()
    }
}
