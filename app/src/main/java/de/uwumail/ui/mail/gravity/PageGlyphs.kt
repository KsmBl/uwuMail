package de.uwumail.ui.mail.gravity

/**
 * The scripts that make a rendered mail hand its letters over.
 *
 * Running script inside a mail body is normally exactly what uwuMail refuses to
 * do. It is safe here because of *when* it runs: the document was parsed with
 * scripting disabled, so the message's own scripts were never executed and
 * turning scripting on afterwards does not go back and run them. The only
 * script that ever runs is this one, and it is turned off again immediately.
 */
object PageGlyphs {

    /**
     * Measures every visible character in the page and returns them as JSON:
     * `[char, left, top, width, height, colour, fontSize, family, weight, italic]`
     * in CSS pixels, with `devicePixelRatio` as the last entry.
     *
     * Characters are measured with a Range rather than estimated from font
     * metrics, so ligatures, kerning and wrapping are already accounted for —
     * the numbers are where the browser actually drew them.
     */
    fun measure(limit: Int, viewportTop: Float, viewportBottom: Float): String = """
        (function() {
          var out = [];
          var range = document.createRange();
          var walker = document.createTreeWalker(
            document.body, NodeFilter.SHOW_TEXT, null, false
          );
          var node;
          while ((node = walker.nextNode()) !== null && out.length < $limit) {
            var text = node.nodeValue;
            if (!text || !/\S/.test(text)) continue;
            var parent = node.parentElement;
            if (!parent) continue;
            var style = window.getComputedStyle(parent);
            if (style.visibility === 'hidden' || style.display === 'none') continue;
            if (parseFloat(style.opacity) === 0) continue;
            var colour = style.color;
            var fontSize = parseFloat(style.fontSize);
            var family = style.fontFamily || '';
            var weight = parseInt(style.fontWeight, 10) || 400;
            var italic = (style.fontStyle === 'italic' || style.fontStyle === 'oblique') ? 1 : 0;
            for (var i = 0; i < text.length && out.length < $limit; i++) {
              var ch = text.charAt(i);
              if (!/\S/.test(ch)) continue;
              range.setStart(node, i);
              range.setEnd(node, i + 1);
              var r = range.getBoundingClientRect();
              if (!r || r.width <= 0 || r.height <= 0) continue;
              // Only what is actually on screen: the rest is not being looked at.
              if (r.bottom < $viewportTop || r.top > $viewportBottom) continue;
              out.push([ch, r.left, r.top, r.width, r.height,
                        colour, fontSize, family, weight, italic]);
            }
          }
          return JSON.stringify({ dpr: window.devicePixelRatio || 1, glyphs: out });
        })();
    """.trimIndent()

    /**
     * Makes the page's own text invisible while leaving everything else — the
     * images, backgrounds, rules and borders — exactly as it was.
     *
     * `-webkit-text-fill-color` is what does it: plain `color` would take the
     * borders and underlines that inherit from it with the glyphs.
     */
    val hideText: String = """
        (function() {
          var style = document.createElement('style');
          style.id = 'uwumail-gravity';
          style.textContent =
            '*{-webkit-text-fill-color:transparent !important;' +
            'text-shadow:none !important;caret-color:transparent !important}';
          document.head.appendChild(style);
        })();
    """.trimIndent()

    /** Puts the text back, for when gravity is switched off again. */
    val showText: String = """
        (function() {
          var style = document.getElementById('uwumail-gravity');
          if (style) style.parentNode.removeChild(style);
        })();
    """.trimIndent()
}
