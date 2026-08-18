package dev.reedd.ui.reader

import org.readium.r2.navigator.epub.EpubNavigatorFragment

/**
 * Forces paragraph indent, heading spacing, and above all **font size** directly
 * onto the DOM, bypassing Readium's own CSS pipeline entirely.
 *
 * This exists because that pipeline cannot be trusted to have run at all. Reading
 * [`ReadiumCss.injectHtml`](https://github.com/readium/kotlin-toolkit) (decompiled
 * and checked directly, not inferred): Readium's own `ReadiumCSS-default.css` --
 * the *only* stylesheet it ships that defines `--RS__flowSpacing`/`--RS__paraIndent`
 * and the rules that consume them -- is linked **only when the page has no
 * `<link>`, `style=`, or `<style>` of its own**. Virtually every real epub has its
 * own CSS, so for a typical book that stylesheet never loads.
 *
 * The font-size symptom is a related but separate problem, and the more important
 * one: moving the text-size slider visibly changed line spacing but not the
 * glyphs themselves. `--USER__fontSize` (Readium's own scaling mechanism) is
 * applied at `:root`/`<html>` and relies on inheritance to reach the actual text.
 * A book whose own CSS gives `<p>` (or `<body>`) an **absolute** font size --
 * `12pt`, `16px`, extremely common in real epubs, especially ones converted from
 * another format -- breaks that inheritance outright: an absolute unit does not
 * recompute from an ancestor's font-size, so the glyphs stay exactly that size no
 * matter what `:root` is told. Line spacing kept moving because it is derived
 * from other, still-inherited values while the font-size itself was pinned.
 *
 * The only way to reliably win against a publisher stylesheet whose specificity
 * (and use of its own `!important`) is unknown is an **inline style with
 * `!important`**, set directly on each element: that combination has the highest
 * priority CSS defines, full stop. A `<style>` tag, however `!important` it is,
 * can still lose to a more specific rule declared earlier in the book's own
 * stylesheet -- which is exactly the failure mode being worked around here.
 */
object TypographyFixer {

    /**
     * @param fontScale [dev.reedd.data.settings.ReaderSettings.fontSize] -- 1.0 is
     *   [BASE_PX] itself. Applied as an absolute pixel size, not inherited or
     *   relative to anything the book's own CSS might also be setting, which is
     *   the entire point: nothing about this can be blocked by an ancestor.
     */
    suspend fun apply(fragment: EpubNavigatorFragment, fontScale: Double) {
        runCatching { fragment.evaluateJavascript(script(fontScale)) }
    }

    private fun script(fontScale: Double): String {
        val baseSizePx = BASE_PX * fontScale
        return """
        (function() {
          try {
            var important = function(el, prop, value) {
              el.style.setProperty(prop, value, 'important');
            };

            var bodySize = '${baseSizePx}px';
            var body = document.querySelectorAll('p, li, blockquote, td, th, div, span, body');
            for (var i = 0; i < body.length; i++) {
              important(body[i], 'font-size', bodySize);
            }

            // Headings keep a visible hierarchy rather than collapsing to body
            // size -- scaled off the same computed base, so they still track the
            // font-size setting the same way body text does.
            var headingScale = { h1: 1.6, h2: 1.4, h3: 1.25, h4: 1.15, h5: 1.05, h6: 1.0 };
            for (var tag in headingScale) {
              var els = document.getElementsByTagName(tag);
              var size = ($baseSizePx * headingScale[tag]) + 'px';
              for (var k = 0; k < els.length; k++) {
                important(els[k], 'font-size', size);
                important(els[k], 'margin-top', '0.4em');
                important(els[k], 'margin-bottom', '0.6em');
                important(els[k], 'text-indent', '0');
              }
            }

            var paragraphs = document.querySelectorAll('p, li');
            for (var j = 0; j < paragraphs.length; j++) {
              important(paragraphs[j], 'text-indent', '1em');
              important(paragraphs[j], 'margin-top', '0');
              important(paragraphs[j], 'margin-bottom', '0.6em');
            }

            // Whatever the very first element in the book is, heading or not, it
            // must carry no top margin of its own -- that margin is what shows up
            // as blank space above the very first line on the very first page.
            var first = document.body.firstElementChild;
            if (first) { important(first, 'margin-top', '0'); }
          } catch (e) {}
          return null;
        })();
        """.trimIndent()
    }

    /** Pixels at fontScale = 1.0. A comfortable default reading size on a phone. */
    private const val BASE_PX = 18.0
}
