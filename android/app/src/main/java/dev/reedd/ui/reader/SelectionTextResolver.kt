package dev.reedd.ui.reader

import android.util.Log
import dev.reedd.diagnostics.CrashReporter
import dev.reedd.domain.NoteLocators
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment

private const val TAG_SELECTION = "ReeddSelection"

/**
 * A selection extended by dragging a handle: the text now spanned, context
 * either side (for [dev.reedd.domain.NoteLocators.extendedLocator]), and
 * where both ends now are on screen (CSS px) so the handles can be
 * repositioned to match.
 */
data class ExtendedSelection(
    val text: String,
    val before: String,
    val after: String,
    val startX: Float,
    val startY: Float,
    val startBottom: Float,
    val endX: Float,
    val endY: Float,
    val endBottom: Float,
)

/**
 * Extends [TapTextResolver]'s single-word highlight into a multi-word one as
 * the reader drags a handle, and disables the browser's own text selection
 * so a stray long-press never triggers it in parallel.
 *
 * A selection here always starts from a tap (see `ReadAlongViewModel.
 * armHandles`) -- there is no long-press-to-select gesture, because Readium's
 * `InputListener` has no long-press timing signal to build one from (`onDrag`
 * only fires once a touch has already moved several pixels, indistinguishable
 * from the start of an ordinary page-turn swipe). Extending is instead always
 * an explicit drag on a small handle, so [extend] only ever needs to resolve
 * two on-screen points into a `Range` -- never to decide *whether* a gesture
 * is a selection at all.
 *
 * DOM node references cannot cross the JS bridge, so unlike a hypothetical
 * "remember the fixed endpoint" design, [extend] re-resolves *both* endpoints
 * fresh, by their CSS-pixel coordinates, on every call.
 */
object SelectionTextResolver {

    suspend fun extend(
        fragment: EpubNavigatorFragment,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
    ): ExtendedSelection? {
        val result = runCatching { fragment.evaluateJavascript(extendScript(startX, startY, endX, endY)) }
        val raw = result.getOrNull()
        if (raw == null) {
            val message = "evaluateJavascript threw: ${result.exceptionOrNull()}"
            Log.w(TAG_SELECTION, message)
            CrashReporter.reportDiagnostic(fragment.requireContext(), TAG_SELECTION, message)
            return null
        }
        // Logged unconditionally, not just on failure -- and pushed to the
        // server's existing crash-report log (see CrashReporter.
        // reportDiagnostic), not just Log.d, because there is no adb/device
        // access available while building this feature. This is the only
        // window into what the WebView actually did with two dragged
        // points.
        Log.d(TAG_SELECTION, "extend($startX, $startY, $endX, $endY) -> $raw")
        CrashReporter.reportDiagnostic(
            fragment.requireContext(),
            TAG_SELECTION,
            "extend($startX, $startY, $endX, $endY) ->\n$raw",
        )
        return parse(raw)
    }

    /**
     * Stops the browser's own long-press-to-select from ever engaging, so it
     * cannot pop up alongside (or instead of) this app's own tap-then-drag
     * selection. Idempotent, and re-appliable per resource load the same way
     * [TapTextResolver]'s own style injections are.
     */
    suspend fun disableNativeSelection(fragment: EpubNavigatorFragment) {
        runCatching {
            fragment.evaluateJavascript(
                """
                (function() {
                  try {
                    if (!document.getElementById('reedd-no-native-selection')) {
                      var style = document.createElement('style');
                      style.id = 'reedd-no-native-selection';
                      style.textContent = 'html, body, * { -webkit-user-select: none; ' +
                        '-moz-user-select: none; -ms-user-select: none; user-select: none; }';
                      document.head.appendChild(style);
                    }
                  } catch (e) {}
                  return null;
                })();
                """.trimIndent()
            )
        }
    }

    fun parse(raw: String): ExtendedSelection? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "\"\"") return null
        val json = runCatching {
            if (trimmed.startsWith("\"")) JSONObject(JSONObject("{\"v\":$trimmed}").getString("v"))
            else JSONObject(trimmed)
        }.getOrNull() ?: return null

        val error = json.optString("error", "")
        if (error.isNotEmpty()) {
            Log.w(TAG_SELECTION, "extend() JS reported: $error")
            return null
        }

        // A drag that pushed one handle past the other -- expected and
        // frequent while dragging near the other handle, not a failure, so
        // this is deliberately not logged as one. Returning null here means
        // the caller's `?.let(onExtendResolved)` simply never runs, which is
        // exactly the no-op the crossing should be: whatever was already
        // displayed (and the raw query position `onHandleMoved` already
        // accumulated) stays as-is, and dragging back the right way resumes
        // normally the moment the points uncross.
        if (json.optBoolean("crossed", false)) return null

        val text = json.optString("text", "")
        if (text.isEmpty()) return null

        return ExtendedSelection(
            text = text,
            before = json.optString("before", ""),
            after = json.optString("after", ""),
            startX = json.optDouble("startX", 0.0).toFloat(),
            startY = json.optDouble("startY", 0.0).toFloat(),
            startBottom = json.optDouble("startBottom", 0.0).toFloat(),
            endX = json.optDouble("endX", 0.0).toFloat(),
            endY = json.optDouble("endY", 0.0).toFloat(),
            endBottom = json.optDouble("endBottom", 0.0).toFloat(),
        )
    }

    private fun extendScript(startX: Float, startY: Float, endX: Float, endY: Float): String = """
        (function() {
          try {
            // Same passthrough guard TapTextResolver.resolve uses, and the same
            // element id -- idempotent either way, and this can run before or
            // after that script has, in either order.
            if (!document.getElementById('reedd-decoration-passthrough-style')) {
              var passthrough = document.createElement('style');
              passthrough.id = 'reedd-decoration-passthrough-style';
              passthrough.textContent = '[id^="r2-decoration-"] { pointer-events: none; }';
              document.head.appendChild(passthrough);
            }

            var a = document.caretRangeFromPoint($startX, $startY);
            var b = document.caretRangeFromPoint($endX, $endY);
            if (!a || !b) {
              return JSON.stringify({ error: 'caretRangeFromPoint returned null', a: !!a, b: !!b });
            }

            // Order-agnostic: the reader can drag either handle past the
            // other, or drag across multiple lines in any direction.
            // a.comparePoint(b) returns -1 when b is BEFORE a, +1 when b is
            // AFTER a -- so order <= 0 means b is the earlier point and must
            // become the range's start, with a as its end (and vice versa
            // for order > 0). Getting this backwards doesn't just misorder
            // the text: DOM Range's own setStart/setEnd each silently drag
            // the *other* boundary along to match whenever the boundary
            // being set would put start after end, so building the range
            // with a and b swapped relative to what comparePoint actually
            // said collapses BOTH boundaries onto the same point on every
            // single call, regardless of which handle moved or which
            // direction -- confirmed live: every one of 200 logged extend()
            // calls came back "range text was empty" until this was fixed.
            var order;
            try {
              order = a.comparePoint(b.startContainer, b.startOffset);
            } catch (cmpErr) {
              return JSON.stringify({ error: 'comparePoint threw: ' + (cmpErr && cmpErr.message) });
            }
            var range = document.createRange();
            try {
              if (order <= 0) {
                range.setStart(b.startContainer, b.startOffset);
                range.setEnd(a.startContainer, a.startOffset);
              } else {
                range.setStart(a.startContainer, a.startOffset);
                range.setEnd(b.startContainer, b.startOffset);
              }
            } catch (rangeErr) {
              return JSON.stringify({ error: 'range build threw: ' + (rangeErr && rangeErr.message) });
            }

            // a is always the point named first in the call (whichever
            // handle is being held fixed or dragged, per how ReaderScreen
            // orders its ExtendRequest -- see ExtendedSelection's own
            // docstring), b the point named second. order <= 0 means b sits
            // at or before a: the handle that was supposed to stay on b's
            // side has been dragged past the one anchored at a. The range
            // above is still built validly either way (DOM Range doesn't
            // care which of a/b is "supposed" to be earlier), but the
            // *meaning* is inverted -- the caller (ReadAlongViewModel.
            // onExtendResolved) treats this as a no-op rather than accepting
            // it, so there is no point painting a highlight or computing
            // context for a result that is about to be discarded.
            if (order <= 0) {
              return JSON.stringify({ crossed: true });
            }
            var text = range.toString();
            if (!text) { return JSON.stringify({ error: 'range text was empty' }); }

            // Paint without touching the DOM -- same highlight TapTextResolver's
            // single-word tap already uses, just repainted with a wider range.
            try {
              if (window.CSS && CSS.highlights) {
                if (!document.getElementById('reedd-highlight-style')) {
                  var style = document.createElement('style');
                  style.id = 'reedd-highlight-style';
                  style.textContent =
                    '::highlight(${TapTextResolver.HIGHLIGHT_NAME}) { background-color: rgba(255, 196, 0, 0.45); }';
                  document.head.appendChild(style);
                }
                CSS.highlights.set('${TapTextResolver.HIGHLIGHT_NAME}', new Highlight(range));
              }
            } catch (e) {}

            // Context either side, resolved independently at each endpoint's
            // own nearest block -- the two ends can be in different
            // paragraphs, so there is no one "block text" to slice in Kotlin
            // the way a single tapped word's is.
            var findBlock = function(node) {
              var block = node.nodeType === 3 ? node.parentElement : node;
              while (block && block !== document.body) {
                var display = window.getComputedStyle(block).display;
                if (display === 'block' || display === 'list-item' ||
                    display === 'table-cell' || display === 'flex') { break; }
                block = block.parentElement;
              }
              return block || document.body;
            };
            var contextChars = ${NoteLocators.CONTEXT_CHARS};
            var before = (function() {
              var block = findBlock(range.startContainer);
              var pre = document.createRange();
              pre.selectNodeContents(block);
              pre.setEnd(range.startContainer, range.startOffset);
              var s = pre.toString();
              return s.slice(Math.max(0, s.length - contextChars));
            })();
            var after = (function() {
              var block = findBlock(range.endContainer);
              var post = document.createRange();
              post.selectNodeContents(block);
              post.setStart(range.endContainer, range.endOffset);
              var s = post.toString();
              return s.slice(0, contextChars);
            })();

            // Caret rects for repositioning both handles -- a one-character-wide
            // range where possible, since a fully collapsed (zero-width) range's
            // bounding rect is unreliable in some Blink versions. The start
            // boundary sits at the *left* edge of the character at startOffset,
            // but the end boundary sits at the *right* edge of the character
            // BEFORE endOffset -- endOffset itself names the character just past
            // the selection. Anchoring the end handle at [endOffset, endOffset+1)
            // (the same recipe as the start handle) would place it one character
            // too far right, and since that drifted point is exactly what gets
            // fed into the next drag tick's caretRangeFromPoint call, the
            // resolved end boundary would creep rightward by a character on
            // every round trip instead of tracking the finger.
            var caretRect = function(container, offset, trailingEdge) {
              var r = document.createRange();
              if (trailingEdge && offset > 0) {
                try {
                  r.setStart(container, offset - 1);
                  r.setEnd(container, offset);
                  var rects = r.getClientRects();
                  var rect = rects.length ? rects[0] : r.getBoundingClientRect();
                  return { left: rect.right, top: rect.top, bottom: rect.bottom };
                } catch (e) { /* fall through to the leading-edge recipe below */ }
              }
              try {
                r.setStart(container, offset);
                r.setEnd(container, Math.min(offset + 1, container.length || offset));
              } catch (e) {
                r.setStart(container, offset);
                r.setEnd(container, offset);
              }
              var rects = r.getClientRects();
              var rect = rects.length ? rects[0] : r.getBoundingClientRect();
              return { left: rect.left, top: rect.top, bottom: rect.bottom };
            };
            var startRect = caretRect(range.startContainer, range.startOffset, false);
            var endRect = caretRect(range.endContainer, range.endOffset, true);

            return JSON.stringify({
              text: text, before: before, after: after,
              startX: startRect.left, startY: startRect.top, startBottom: startRect.bottom,
              endX: endRect.left, endY: endRect.top, endBottom: endRect.bottom
            });
          } catch (e) {
            return JSON.stringify({ error: 'uncaught: ' + (e && e.message) });
          }
        })();
    """.trimIndent()
}
