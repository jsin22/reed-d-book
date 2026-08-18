package dev.reedd.ui.reader

import org.readium.r2.navigator.epub.EpubNavigatorFragment

/**
 * Continuous-scroll's own way of keeping the current sentence in view.
 *
 * Paginated mode jumps to a new *page* only when the sentence being read is no
 * longer the one on screen (see the `navigateTo` effect in `ReaderScreen.kt`) --
 * there is no equivalent "off screen" to react to in scroll mode, since it is one
 * continuous document and the reader can be looking anywhere on it. Snapping to
 * every new sentence the way paginated mode does meant a small scroll correction
 * after every single sentence, reported as distracting -- the opposite of what
 * following along should feel like.
 *
 * Instead: let the highlighted sentence drift down the screen as reading
 * continues, the way it naturally would with no auto-scroll at all, and only
 * catch up once it has drifted about three-quarters of the way down -- at which
 * point it is brought back to the top in one motion, teleprompter style, rather
 * than a correction after every sentence.
 *
 * This works directly against the rendered DOM rather than by re-resolving a
 * Locator and calling `go()`. Decompiling Readium's `HtmlDecorationTemplate`
 * (`createUniqueClassName`) shows its Highlight-style decoration element is
 * always named `r2-highlight-<n>` -- the number is assigned once per session and
 * not worth depending on, hence the wildcard match below. This app applies
 * exactly one Highlight decoration at a time -- the sentence currently being
 * read, via `ReadAlongLocators.DECORATION_GROUP` -- so that selector always
 * finds the right element with no ambiguity. Measuring it directly is also what
 * makes "75% down the screen" mean the actual rendered position rather than an
 * estimate; BUGS.md's BUG-15 tried a progression-based estimate for a related
 * problem in page mode and it overshot.
 */
object ScrollFollower {

    suspend fun scrollToTopIfPastThreshold(fragment: EpubNavigatorFragment) {
        runCatching { fragment.evaluateJavascript(SCRIPT) }
    }

    /** Fraction of the viewport height the highlight may drift down before catching up. */
    private const val THRESHOLD_FRACTION = 0.75

    private val SCRIPT = """
        (function() {
          try {
            var el = document.querySelector('[class*="r2-highlight-"]');
            if (!el) return null;
            var rect = el.getBoundingClientRect();
            if (rect.top >= window.innerHeight * $THRESHOLD_FRACTION) {
              el.scrollIntoView({ block: 'start', behavior: 'instant' });
            }
          } catch (e) {}
          return null;
        })();
    """.trimIndent()
}
