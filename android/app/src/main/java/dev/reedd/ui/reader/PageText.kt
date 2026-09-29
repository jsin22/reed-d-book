package dev.reedd.ui.reader

import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/**
 * A chapter's text as the WebView renders it, and positions within it.
 *
 * "The text" is every text node under `<body>` in document order, skipping
 * `<script>`/`<style>` -- the same string jsoup's `body.wholeText()` gave the
 * aligner (see `EpubTextExtractor`), so a character position here and a
 * sentence's aligned position describe the same text. Readium only appends
 * text-free decoration containers to the body, so it does not shift positions.
 */
object PageText {

    /**
     * JS helpers shared by the tap and selection scripts, so both measure
     * positions exactly the way [read] builds the text.
     */
    internal const val HELPERS = """
        function reeddTextNodes() {
          var nodes = [];
          var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
            acceptNode: function(n) {
              for (var p = n.parentNode; p && p !== document.body; p = p.parentNode) {
                var tag = (p.nodeName || '').toUpperCase();
                if (tag === 'SCRIPT' || tag === 'STYLE') { return NodeFilter.FILTER_REJECT; }
              }
              return NodeFilter.FILTER_ACCEPT;
            }
          });
          var n;
          while ((n = walker.nextNode())) { nodes.push(n); }
          return nodes;
        }
        function reeddPageOffset(node, offset) {
          if (!node || node.nodeType !== 3) { return -1; }
          var nodes = reeddTextNodes(), total = 0;
          for (var i = 0; i < nodes.length; i++) {
            if (nodes[i] === node) { return total + offset; }
            total += nodes[i].data.length;
          }
          return -1;
        }
    """

    private val READ_SCRIPT = """
        (function() {
          try {
            $HELPERS
            return reeddTextNodes().map(function(n) { return n.data; }).join('');
          } catch (e) { return null; }
        })();
    """.trimIndent()

    /** The current chapter's text, or null if the WebView could not say. */
    suspend fun read(fragment: EpubNavigatorFragment): String? {
        val raw = runCatching { fragment.evaluateJavascript(READ_SCRIPT) }.getOrNull()?.trim() ?: return null
        if (raw.isEmpty() || raw == "null") return null
        // evaluateJavascript hands back the result JSON-encoded: a quoted string.
        return runCatching { JSONObject("{\"v\":$raw}").getString("v") }.getOrNull()
    }
}
