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

    /** Applied by [resolve]; cleared by [clearHighlight]. Not private:
     *  [SelectionTextResolver] repaints this same highlight as a drag
     *  extends a selection, rather than using a second one, so [clearHighlight]
     *  already works unchanged for that case too. */
    internal const val HIGHLIGHT_NAME = "reedd-word"

    /**
     * How far, in CSS pixels, a tap may sit outside the word `caretRangeFromPoint`
     * resolved before that word is rejected as a miss rather than a hit.
     *
     * `caretRangeFromPoint` returns the *nearest* caret position, full stop -- it
     * does not report "nothing here" for blank space, it reports whatever text is
     * closest, which can be centimetres away in a paginated, multi-column layout.
     * A few pixels is enough slack for font-metric rounding without accepting that
     * kind of nearest-neighbour guess.
     */
    private const val TAP_TOLERANCE_PX = 6

    suspend fun resolve(fragment: EpubNavigatorFragment, x: Float, y: Float): TappedWord? {
        val raw = runCatching { fragment.evaluateJavascript(script(x, y)) }.getOrNull() ?: return null
        return parse(raw)
    }

    /**
     * Paints [HIGHLIGHT_NAME] over a passage found by its stored text --
     * for jumping to a note from [NotesSheet] and showing what was actually
     * highlighted, not just which page it's on. No handles, no menu: this
     * is display-only, cleared the same way a tap's highlight already is
     * (a plain tap elsewhere, or the next word tapped).
     *
     * Finds the passage by searching the resource's own text for `before +
     * text + after` verbatim first (the same context a tap/drag-extended
     * note locator already stores, disambiguating a word or phrase that
     * repeats in the same chapter), falling back to matching [text] alone
     * if that exact concatenation isn't found -- block-boundary whitespace
     * can differ subtly from how [before]/[after] were originally sliced
     * out of a single block's `textContent`, so demanding an exact context
     * match is worth relaxing rather than silently failing to highlight
     * anything.
     */
    suspend fun highlightPassage(fragment: EpubNavigatorFragment, text: String, before: String, after: String): Boolean {
        val raw = runCatching { fragment.evaluateJavascript(highlightScript(text, before, after)) }.getOrNull()
            ?: return false
        return raw.trim().trim('"') == "true"
    }

    private fun highlightScript(text: String, before: String, after: String): String {
        // JSONObject.quote produces a properly escaped, quoted JS string
        // literal -- required here since, unlike every other script in this
        // file/SelectionTextResolver, these values are arbitrary stored note
        // text (can contain quotes, backslashes, newlines), not numbers.
        val textLit = JSONObject.quote(text)
        val beforeLit = JSONObject.quote(before)
        val afterLit = JSONObject.quote(after)
        return """
            (function() {
              try {
                var text = $textLit, before = $beforeLit, after = $afterLit;
                var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
                var nodes = [];
                var full = '';
                var node;
                while (node = walker.nextNode()) {
                  nodes.push({ node: node, start: full.length });
                  full += node.data;
                }
                var idx = full.indexOf(before + text + after);
                var matchStart, matchEnd;
                if (idx >= 0) {
                  matchStart = idx + before.length;
                  matchEnd = matchStart + text.length;
                } else {
                  idx = full.indexOf(text);
                  if (idx < 0) { return false; }
                  matchStart = idx;
                  matchEnd = idx + text.length;
                }
                var locate = function(charIndex) {
                  for (var i = nodes.length - 1; i >= 0; i--) {
                    if (nodes[i].start <= charIndex) {
                      return { node: nodes[i].node, offset: charIndex - nodes[i].start };
                    }
                  }
                  return null;
                };
                var start = locate(matchStart), end = locate(matchEnd);
                if (!start || !end) { return false; }
                var range = document.createRange();
                range.setStart(start.node, start.offset);
                range.setEnd(end.node, end.offset);
                if (!(window.CSS && CSS.highlights)) { return false; }
                if (!document.getElementById('reedd-highlight-style')) {
                  var style = document.createElement('style');
                  style.id = 'reedd-highlight-style';
                  style.textContent =
                    '::highlight($HIGHLIGHT_NAME) { background-color: rgba(255, 196, 0, 0.45); }';
                  document.head.appendChild(style);
                }
                CSS.highlights.set('$HIGHLIGHT_NAME', new Highlight(range));
                return true;
              } catch (e) {
                return false;
              }
            })();
        """.trimIndent()
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
            // Readium's decoration overlays (e.g. the currently-spoken-sentence
            // highlight, group "readalong") are real elements painted above the
            // text -- Readium's own bundle never sets pointer-events on them. Left
            // alone, caretRangeFromPoint hit-tests paint order and lands on the
            // decoration instead of the text underneath, so a tap on whichever
            // sentence happens to be highlighted right now silently resolves to
            // nothing. Done first, unconditionally, so it is in effect before the
            // very first caretRangeFromPoint call below -- not just future ones.
            if (!document.getElementById('reedd-decoration-passthrough-style')) {
              var passthrough = document.createElement('style');
              passthrough.id = 'reedd-decoration-passthrough-style';
              passthrough.textContent = '[id^="r2-decoration-"] { pointer-events: none; }';
              document.head.appendChild(passthrough);
            }

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

            // caretRangeFromPoint reports the *nearest* caret position, even when
            // the tap did not land on any text at all: blank space below the last
            // line, a side margin, or -- worse, under column-based pagination -- a
            // position that belongs to a different page entirely. Trust it only
            // when the tap point actually falls inside the word's own rendered
            // box; otherwise this is a miss, not a hit, and must not fabricate an
            // answer just because *something* was nearby.
            var rect = range.getBoundingClientRect();
            var pad = $TAP_TOLERANCE_PX;
            if ($x < rect.left - pad || $x > rect.right + pad ||
                $y < rect.top - pad || $y > rect.bottom + pad) {
              return null;
            }

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
