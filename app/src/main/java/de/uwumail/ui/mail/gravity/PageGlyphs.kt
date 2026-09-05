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

    /** The class put on the text that has been handed over, and only that text. */
    private const val MARK = "uwumail-lifted"

    /** The same, for pictures, which need hiding outright rather than uninking. */
    private const val IMAGE_MARK = "uwumail-lifted-img"

    /**
     * Measures every visible character in the page and returns them as JSON:
     * `[char, left, top, width, height, colour, fontSize, family, weight, italic]`
     * in CSS pixels, alongside `devicePixelRatio`.
     *
     * Characters are measured with a Range rather than estimated from font
     * metrics, so ligatures, kerning and wrapping are already accounted for —
     * the numbers are where the browser actually drew them.
     *
     * Text is taken a whole node at a time and each node that was taken is
     * marked, so that hiding can be confined to exactly what was lifted. A node
     * that would not fit within [limit] is left alone rather than half taken:
     * anything hidden and not falling has simply vanished from the message, and
     * a footer or a quoted reply disappearing is not the effect.
     *
     * Marking happens after all the measuring, so every rect is of the
     * untouched layout.
     */
    fun measure(limit: Int, viewportTop: Float, viewportBottom: Float): String {
        val MIN_IMAGE = GlyphReader.MIN_IMAGE
        return """
        (function() {
          var out = [];
          var taken = [];
          var range = document.createRange();
          var walker = document.createTreeWalker(
            document.body, NodeFilter.SHOW_TEXT, null, false
          );
          var pictures = [];
          var images = document.images || [];
          for (var m = 0; m < images.length; m++) {
            var img = images[m];
            var ir = img.getBoundingClientRect();
            if (!ir || ir.width < $MIN_IMAGE || ir.height < $MIN_IMAGE) continue;
            if (ir.bottom < $viewportTop || ir.top > $viewportBottom) continue;
            var istyle = window.getComputedStyle(img);
            if (istyle.visibility === 'hidden' || istyle.display === 'none') continue;
            // A picture that never loaded is a broken icon, not a picture.
            if (img.complete === false || img.naturalWidth === 0) continue;
            pictures.push([ir.left, ir.top, ir.width, ir.height]);
            img.classList.add('$IMAGE_MARK');
          }

          var node;
          while ((node = walker.nextNode()) !== null) {
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

            var batch = [];
            for (var i = 0; i < text.length; i++) {
              var ch = text.charAt(i);
              if (!/\S/.test(ch)) continue;
              range.setStart(node, i);
              range.setEnd(node, i + 1);
              var r = range.getBoundingClientRect();
              if (!r || r.width <= 0 || r.height <= 0) continue;
              // Only what is on screen: the rest cannot be scrolled to while
              // the letters are loose, and could not fall anywhere anyway.
              if (r.bottom < $viewportTop || r.top > $viewportBottom) continue;
              batch.push([ch, r.left, r.top, r.width, r.height,
                          colour, fontSize, family, weight, italic]);
            }
            if (batch.length === 0) continue;
            if (out.length + batch.length > $limit) break;
            for (var k = 0; k < batch.length; k++) out.push(batch[k]);
            taken.push(node);
          }

          for (var n = 0; n < taken.length; n++) {
            var t = taken[n];
            if (!t.parentNode) continue;
            var span = document.createElement('span');
            span.className = '$MARK';
            t.parentNode.insertBefore(span, t);
            span.appendChild(t);
          }
          return JSON.stringify({
            dpr: window.devicePixelRatio || 1, glyphs: out, images: pictures
          });
        })();
        """.trimIndent()
    }

    /**
     * Makes the handed-over text invisible and nothing else — the images,
     * backgrounds, rules and borders stay, and so does any text that was not
     * taken.
     *
     * `-webkit-text-fill-color` is what does it: plain `color` would take the
     * borders and underlines that inherit from it with the glyphs.
     */
    val hideText: String = """
        (function() {
          var style = document.createElement('style');
          style.id = 'uwumail-gravity';
          style.textContent =
            '.$MARK{-webkit-text-fill-color:transparent !important;' +
            'text-shadow:none !important;caret-color:transparent !important}' +
            '.$IMAGE_MARK{visibility:hidden !important}';
          (document.head || document.documentElement).appendChild(style);
        })();
    """.trimIndent()

    /** Puts the text back, for when gravity is switched off again. */
    val showText: String = """
        (function() {
          var style = document.getElementById('uwumail-gravity');
          if (style && style.parentNode) style.parentNode.removeChild(style);
          var pictures = document.querySelectorAll('.$IMAGE_MARK');
          for (var p = 0; p < pictures.length; p++) {
            pictures[p].classList.remove('$IMAGE_MARK');
          }
          var marked = document.querySelectorAll('span.$MARK');
          for (var i = 0; i < marked.length; i++) {
            var span = marked[i];
            var parent = span.parentNode;
            if (!parent) continue;
            while (span.firstChild) parent.insertBefore(span.firstChild, span);
            parent.removeChild(span);
            parent.normalize();
          }
        })();
    """.trimIndent()
}
