package dev.reedd.ui.reader

import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/** The word under the reader's finger, and where it is on screen. */
data class TappedWord(
    /** The word itself, as it appears on the page. */
    val word: String,
    /** Text content of the block element containing it. */
    val blockText: String,
    /** Character offset of the word within [blockText]. */
    val offset: Int,
    /** The word's box in **CSS pixels**, for positioning a menu beside it. */
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * Asks the WebView which word is under a tap, and highlights just that word.
 *
 * `caretRangeFromPoint` is a Blink API and an Android WebView is Blink, so it is
 * available. The word is found by expanding the caret out to whitespace in the text
 * node, which keeps apostrophes and hyphens ("don't", "well-known") intact.
 *
 * The offset is measured from the start of the *block* rather than the text node,
 * because a sentence routinely spans several nodes — one `<em>` is enough — and a
 * node-relative offset would be meaningless to the caller.
 *
 * Highlighting uses the **CSS Custom Highlight API**, which paints a range without
 * touching the DOM. That matters: wrapping the word in a `<span>` would mutate the
 * document Readium is tracking positions in, and injecting elements under a
 * pagination engine invites subtle breakage.
 */
object TapTextResolver {

    /** Applied by [resolve]; cleared by [clearHighlight]. */
    private const val HIGHLIGHT_NAME = "reedd-word"

    suspend fun resolve(fragment: EpubNavigatorFragment, x: Float, y: Float): TappedWord? {
        val raw = runCatching { fragment.evaluateJavascript(script(x, y)) }.getOrNull() ?: return null
        return parse(raw)
    }

    /** Removes the word highlight, e.g. when the menu is dismissed. */
    suspend fun clearHighlight(fragment: EpubNavigatorFragment) {
        runCatching {
            fragment.evaluateJavascript(
                "(function(){ try { if (window.CSS && CSS.highlights) " +
                    "CSS.highlights.delete('$HIGHLIGHT_NAME'); } catch (e) {} return null; })();"
            )
        }
    }

    /**
     * `evaluateJavascript` returns a *JSON-encoded* value, so a returned string
     * arrives quoted and escaped, and "nothing" may be `null` or the text `null`.
     */
    fun parse(raw: String): TappedWord? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "\"\"") return null
        val json = runCatching {
            if (trimmed.startsWith("\"")) JSONObject(JSONObject("{\"v\":$trimmed}").getString("v"))
            else JSONObject(trimmed)
        }.getOrNull() ?: return null

        val word = json.optString("word", "")
        val text = json.optString("text", "")
        val offset = json.optInt("offset", -1)
        if (word.isBlank() || text.isEmpty() || offset < 0) return null

        return TappedWord(
            word = word,
            blockText = text,
            offset = offset.coerceAtMost(text.length),
            left = json.optDouble("left", 0.0).toFloat(),
            top = json.optDouble("top", 0.0).toFloat(),
            right = json.optDouble("right", 0.0).toFloat(),
            bottom = json.optDouble("bottom", 0.0).toFloat(),
        )
    }

    private fun script(x: Float, y: Float): String = """
        (function() {
          try {
            var caret = document.caretRangeFromPoint($x, $y);
            if (!caret) { return null; }
            var node = caret.startContainer;
            if (node.nodeType !== 3) { return null; }   // only text nodes hold words
            var data = node.data || '';
            var i = caret.startOffset;

            // Expand out to whitespace: apostrophes and hyphens stay part of the word.
            var start = i, end = i;
            var isWord = function(c) { return c && !/\s/.test(c); };
            while (start > 0 && isWord(data[start - 1])) { start--; }
            while (end < data.length && isWord(data[end])) { end++; }
            var word = data.slice(start, end);
            if (!word || !/[A-Za-zÀ-ɏ]/.test(word)) { return null; }

            var range = document.createRange();
            range.setStart(node, start);
            range.setEnd(node, end);

            // Nearest block, so the text returned is a whole paragraph rather than
            // one styled fragment of one.
            var block = node.parentElement;
            while (block && block !== document.body) {
              var display = window.getComputedStyle(block).display;
              if (display === 'block' || display === 'list-item' ||
                  display === 'table-cell' || display === 'flex') { break; }
              block = block.parentElement;
            }
            if (!block) { block = document.body; }

            var pre = document.createRange();
            pre.selectNodeContents(block);
            pre.setEnd(node, start);
            var offset = pre.toString().length;

            // Paint the word without touching the DOM. Unsupported is not fatal:
            // the menu still works, there is simply no highlight.
            try {
              if (window.CSS && CSS.highlights) {
                if (!document.getElementById('reedd-highlight-style')) {
                  var style = document.createElement('style');
                  style.id = 'reedd-highlight-style';
                  style.textContent =
                    '::highlight($HIGHLIGHT_NAME) { background-color: rgba(255, 196, 0, 0.45); }';
                  document.head.appendChild(style);
                }
                CSS.highlights.set('$HIGHLIGHT_NAME', new Highlight(range));
              }
            } catch (e) {}

            var rect = range.getBoundingClientRect();
            return JSON.stringify({
              word: word, text: block.textContent || '', offset: offset,
              left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom
            });
          } catch (e) {
            return null;
          }
        })();
    """.trimIndent()
}
