# Bugs

A running log. **Add bugs to the "Reported" section** — copy the template below,
fill in what you can, and leave the rest; a one-line note with the build number is
far more useful than nothing. Anything in Reported gets triaged, diagnosed and moved
down to Open or Fixed.

## How to file one

Copy this and paste it under [Reported](#reported):

```markdown
### What went wrong in a few words
- **Where**: library / import / reader / player / settings / server
- **Build**: date or sha from the APK (see below)
- **Did**: what you were doing
- **Saw**: what actually happened
- **Wanted**: what you expected instead
- **Every time?**: yes / no / only when ...
```

Only *Saw* is essential. The rest is a nudge, not a form.

**Getting the build number** — on the device itself, Settings → scroll to the bottom.
Every APK is stamped with the git commit and time it was built (`Build a1b2c3d4 ·
2026-08-17 15:26`), so this works after the file has already been copied to the phone,
which a file timestamp does not — copying rarely preserves `mtime`, and there is no
way to inspect it from the phone anyway. From the build machine, the same commit is
also visible without installing anything:

```sh
stat -c '%y' android/app/build/outputs/apk/debug/app-debug.apk   # when it was built
git describe --always --dirty --abbrev=8                        # what it was built from
```

Builds have been changing several times a day, so which one you were on is usually
the single most useful field.

**If the app crashed**, you do not need to write the trace out. It is captured
automatically: the app shows it on the next launch with a Copy button, and posts it
to the server, so it can be read with

```sh
curl -s http://127.0.0.1:8000/api/diagnostics/crashes
```

Paste the first few lines here and that is plenty.

---

## Reported

*Nothing waiting. Add new bugs here.*

---

## Open

### BUG-15 — A long sentence leaves the page behind the audio

**Reported** 2026-08-18, once BUG-13 was actually fixed and the margin was
finally small enough to notice. **Attempted and reverted** 2026-08-18, at the
user's request -- the fix made things worse, not better. **Severity** medium.

**Symptom** "when in page mode if a sentence extends from one page to the next
the page doesn't flip to the next page until the sentence finishes and the next
sentence starts and is highlighted... the audio is reading but i'm still looking
at the previous page."

**Cause.** Follow-mode navigation (`ReaderScreen.kt`'s `navigateTo` effect) only
fires when the *chunk index* changes -- correct for jumping to wherever a
sentence starts, but a sentence that runs onto the next page gets no further
navigation until that whole sentence finishes and the next one begins. For as
long as one sentence spans two pages, nothing tells the reader to catch up.
There is no word-level timing available to know precisely when the words being
read leave the page -- audiblez times sentences, not words -- so a precise fix
is not on the table without a backend change.

**What was tried, and why it made things worse.** A second `LaunchedEffect`
checked once a second (while following and playing) whether the current chunk's
own `progression` (`SyncChunkEntity.progression`, the same approximate field
`ReadAlongLocators.locator` already uses for navigation) had pulled meaningfully
ahead of the displayed page's own progression, and called `fragment.goForward()`
when it had. Reported result: **overshoots by a page** -- worse than the original
bug, not better. Most likely cause, not yet confirmed: `progression` is only
"roughly how far through the resource" a sentence is (its own doc comment says
so), estimated at alignment time from character offset, not from anything
Readium itself agrees is where that text renders. A page correctly showing the
tail end of a sentence can easily already read as "ahead of" that sentence's own
progression estimate by more than the slop this used, especially right after the
boundary jump the *other* effect just made -- meaning the two effects could
plausibly compound rather than the second one only firing when genuinely needed.
Reverted in full: the `LaunchedEffect`, both of its constants
(`MID_SENTENCE_CHECK_INTERVAL_MS`, `MID_SENTENCE_PROGRESSION_SLOP`), and the now
unused `kotlinx.coroutines.isActive` import.

**What is worth trying next, if picked up again:** something that can tell
*correctly* rendered pages apart from stale ones, rather than a threshold on an
already-approximate number. Readium's `firstVisibleElementLocator()`
(`VisualNavigator`, already used nowhere in this app yet) returns what is
*actually* on screen right now, which is a fundamentally different -- and
probably more trustworthy -- signal than comparing two independently-estimated
progression values against each other.

### BUG-2 — The page does not turn to follow the audio

**Reported** 2026-08-07. **Severity** high — this is the core of Phase 4's last requirement.

**Symptom** "the page doesn't flip automatically as the text being read moves to the
next page".

**Cause — not yet established.** Candidates, most likely first:

1. **Follow mode is being switched off immediately.** An `InputListener` is
   registered whose `onDrag` calls `onUserDragged()`
   (`ui/reader/ReaderScreen.kt`, the `LaunchedEffect(navigator)` block). If Readium
   emits drag events for its own scrolling or for any touch — including the taps
   used to turn a page — following is disabled within seconds of opening the book
   and never re-engages. The `FollowController` logic itself is unit-tested and
   correct; the suspicion is the *signal* feeding it.
   *Check:* does the bar show "Not following the audio. Tap the crosshair to jump
   back to it."? If so this is the cause. If the crosshair is lit and the page still
   does not move, it is not.
2. **`go(locator)` is not resolving.** The locator is a text-quote anchor plus an
   approximate `progression`. If Readium's `go()` needs more than that in paginated
   mode it may silently do nothing — it returns a `Boolean` that the code currently
   ignores.
3. **The navigation event is being dropped.** `navigateTo` is a
   `MutableStateFlow<Int?>` set to an index and then cleared to null by
   `onNavigationHandled`. If two sentence changes land within one recomposition the
   second could be lost, though that would cause *occasional* misses rather than
   nothing ever happening.

**Next step** log the return value of `go()` and whether `onDrag` fires unprompted,
with `adb logcat --pid=$(adb shell pidof -s dev.reedd.debug)`. Fix BUG-3 first so
the highlight is visible while testing.

---

### BUG-4 — Conversion is too slow

**Reported** 2026-08-07. **Severity** medium — a known limitation rather than a defect, but it makes real
books impractical.

**Symptom** "takes too long to convert an epub".

**Cause — confirmed.** The TTS runs entirely on CPU. `torch.__version__` is
**`2.13.0+cpu`**, `torch.cuda.is_available()` is `False`, and `torch.version.hip` is
`None`, so the Pocket 4's Radeon 890M is not being used at all. This is the open
item from project_plan Phase 2 ("Modify the environment to leverage AMD's ROCm or
ONNX Runtime (DirectML)"), already noted in `audiblez/SYNC.md`.

**Measured throughput** from this session's worker log, converting
`sample-short.epub` (66.3 s of audio) four times: 13.0 s, 14.0 s, 14.3 s, and one
earlier run at 13.0 s — call it **~5× faster than realtime**. Extrapolating:

| audiobook length | expected conversion time on CPU |
|---|---|
| 1 hour | ~12 min |
| 10 hours (a typical novel) | **~2 hours** |
| 30 hours | ~6 hours |

The `sample-medium.epub` runs took 33–34 s each.

**Fix directions**

1. **ROCm.** Install a ROCm torch build in `audiblez/.venv`. RDNA 3.5 (gfx1150)
   usually needs `HSA_OVERRIDE_GFX_VERSION=11.0.0`; whether the 890M is workable
   with current ROCm is the thing to establish first, since an unsupported iGPU can
   be slower than CPU or simply fail.
2. **ONNX Runtime.** Kokoro has ONNX exports, and ONNX Runtime with a DirectML or
   ROCm provider avoids the torch/ROCm problem entirely. Likely the more reliable
   route on this hardware, at the cost of changing how audiblez loads the model.
3. **Cheap wins regardless of backend:** the worker is pinned to
   `--concurrency=1` because TTS saturates the machine, but per-sentence batching
   inside Kokoro is not being exploited — batching sentences into one forward pass
   would help on CPU too.

Whatever the backend, the sync metadata must keep coming from frame counts
(`len(audio) / 24000`) and not from a wall clock, or the timings stop matching the
audio.

---

---

## Fixed

Kept rather than deleted: each one records *why* it happened, which is the part worth
having when something similar shows up later.


### BUG-16 — Continuous scroll re-centered after every single sentence

**Reported** 2026-08-18. **Fixed** 2026-08-18. **Severity** low, but the exact
kind of small friction that adds up over a whole book.

**Symptom** "in continuous scroll mode, the scrolling is a little distracting
since it scrolls up after each sentence."

**Cause.** Continuous scroll shared the same follow-mode navigation as page
mode (`ReaderScreen.kt`'s `navigateTo` effect): every time the chunk index
changed, it called `fragment.go(locator, animated = false)`. In page mode that
is a page turn -- a single, discrete event, exactly what "following" should do.
In scroll mode the same call becomes a scroll jump on *every sentence*, which
reads as constant small corrections rather than a natural reading motion.

**Fix.** Scroll mode no longer reacts to the per-sentence `navigateTo` event for
movement *within* a chapter (the effect now checks `settings.scroll` and skips
the `go()` call, while still consuming the event so follow-state bookkeeping
stays correct). In its place, a new effect (`ScrollFollower.kt`) polls every
400ms while following and asks: has the sentence currently being read drifted
about three-quarters of the way down the screen? If so, it is scrolled back to
the top in one motion -- "teleprompter" style, per the user's own description
of what they wanted.

**Follow-up, same day: chapter boundaries stopped advancing.** The first cut of
this fix skipped `go()` unconditionally whenever `settings.scroll` was true.
Reported: "when it scrolls to the bottom and switches to the next page, the
page doesn't flip but the audio continues... when i manually flip to the next
page i see the cursor on the next page and when i manually flip back i also see
the cursor stuck on the sentence of the last page." Cause: `go()` is not only a
scroll position -- crossing into the next chapter means the target locator's
`href` names a different resource than what is currently loaded in
`EpubNavigatorFragment`'s resource pager (`R2ViewPager`, decompiled), and only
`go()` knows how to page the pager there; `ScrollFollower`'s `scrollIntoView`
can only move within whatever WebView is already on screen. Skipping `go()`
unconditionally left the pager stuck on the old chapter forever once the
audio's chunk index crossed into the next one -- explaining both halves of the
report: the never-advanced page, and the stale highlight left behind on it once
the app *did* apply the new decoration to a page the user was not looking at.
Fixed by comparing hrefs: `go()` still runs in scroll mode whenever the target
locator's href differs from `fragment.currentLocator.value.href`, i.e. exactly
on a chapter boundary, and is skipped only for same-chapter movement, where
`ScrollFollower` is what should be moving the page.

The check works directly against the rendered DOM rather than through a
Locator: Readium's own Highlight-style decoration element is always named
`r2-highlight-<n>` (decompiled from `HtmlDecorationTemplate.createUniqueClassName`
-- the number is assigned once per session, hence the wildcard class match),
and this app applies exactly one Highlight decoration at a time -- the sentence
being read -- so `[class*="r2-highlight-"]` always finds the right element with
no ambiguity. Reading its real `getBoundingClientRect()` and comparing to
`window.innerHeight` measures the actual on-screen position, not an estimate --
notably different from BUG-15's approach for a related page-mode problem, whose
`progression`-based estimate overshot.

Unverified without a device: whether 75% and a 400ms poll interval feel right
in practice, and whether `scrollIntoView` interacts cleanly with the reader's
own scroll container in every book. Easy to retune (`THRESHOLD_FRACTION` and
`SCROLL_FOLLOW_CHECK_INTERVAL_MS`) if not.

### BUG-14 — The page-number overlay was covered once the margin fix landed

**Reported** 2026-08-18, same report as BUG-15. **Fixed** 2026-08-18.
**Severity** low, cosmetic -- but a direct side effect of BUG-13's own fix, worth
recording next to it.

**Symptom** "the top and bottom margins are so small that at the bottom the text
covers the page indicator, i.e. 6 / 29."

**Cause.** The "n / total" overlay is plain Compose `Text`, `Alignment.BottomCenter`,
in the same `Box` as the reader's `AndroidFragment`. Before BUG-13's fix, Readium's
own (redundant) inset padding kept book text away from the bottom edge, which
incidentally also kept it clear of the indicator. Once that padding was correctly
removed, text could reach the physical bottom edge -- including underneath the
indicator. Giving the indicator a background would not have fixed this: an
`AndroidFragment`'s view is a separate compositing layer that always draws above
ordinary Compose-drawn content in the same `Box`, regardless of declaration order,
so a WebView with the tools available to look "z-index-below" a background is not
actually possible here.

**Fix** a real Compose-layout reservation instead: the navigator's own modifier
now carries `.padding(bottom = 24.dp)`, sized for the indicator's `labelSmall`
text plus its own 2dp padding, so the WebView's content genuinely never occupies
that strip rather than trying to win a layer-ordering fight it cannot win.

### BUG-12 — Text sat in a narrow, centered column instead of filling the screen

**Reported** 2026-08-17. **Fixed** 2026-08-17. **Severity** medium — readable, but a
poor use of a phone screen, and it made the font-size setting behave strangely.

**Symptom** "the text doesn't take up the whole viewing area. the text is centered
in the middle. It should start at the top of the viewing area and if i make the
text smaller it should fit more text on the screen instead of just making the
margins bigger."

**Cause — confirmed by extracting and reading Readium's bundled stylesheet**
(`readium-navigator-3.3.0.aar`, `assets/readium/readium-css/*/ReadiumCSS-after.css`).
Its default reflowable rules are:

```css
:root { --RS__maxLineLength: 40rem; --RS__pageGutter: 20px; }
body {
  max-width: var(--RS__maxLineLength) !important;
  padding: 0 var(--RS__pageGutter) !important;
  margin: 0 auto !important;
}
```

`maxLineLength` caps the reading column's width — a real, useful feature on a wide
desktop window, where an uncapped line of text would be uncomfortably long to track
across — and `margin: 0 auto` centers that capped column when the viewport is wider
than it. E-4 (`en.md`, 2026-08-12) had already lowered `pageGutter` and added a
**Margins** slider, but neither touches `maxLineLength`, which the app never
overrode; it was running at Readium's own default the entire time. On a phone,
40rem (640px at the base font size) is comfortably wider than the screen, so the
column was capped and centered with dead space on both sides rather than filling
it. And because the cap is in `rem` — relative to font size, not an absolute pixel
value — turning the font size down shrank the capped column by the same
proportion instead of fitting more text: font size was controlling how much blank
margin there was, not how much text was on screen, which is exactly the second
half of the report.

Vertical placement was never actually wrong — nothing in the stylesheet centers
content vertically, and columns fill top-down (`column-fill: auto`). A narrow,
centered column of text with dead space on three sides is what reads as "the text
starts in the middle of the screen" even though, strictly, it starts at its own
top-left corner; fixing the width fixes the visual impression of both.

**Fix** `RsProperties(maxLineLength = Length.Rem(100.0), ...)` in
`EpubNavigatorFragment.Configuration` (`ReaderScreen.kt`) — high enough that it
cannot bind on any phone or tablet at any supported font scale
(`ReaderSettings.MAX_FONT_SIZE` = 2.5x), so the column is simply the full viewport
width and `margin: auto` has nothing left to center within. The **Margins** slider
and `ReaderSettings.pageMargins` are removed entirely — `pageGutter` is now a fixed
8px, not a user preference, since once the column is no longer artificially narrow
there is nothing left worth tuning there.

### BUG-13 — Paginated mode's height was stale from before the transport bar claimed its space

**Reported** 2026-08-18. **Fixed** 2026-08-18. **Severity** high.
**Unverified on a device**, same standing caveat.

**Symptom** — the report that finally separated this from BUG-12: "when i put it
in fullscreen mode the text fills up most of the viewing area. also when its in
continuous scroll mode, the text moves up in the viewing area. only when its in
page mode and not full screen does the issue happen." A completely different bug
from BUG-12's four rounds, and this one line of description did more to isolate
it than any amount of decompiling: not a CSS/stylesheet problem at all (scroll
mode uses the exact same book, same stylesheet loading, same publisher CSS, and
is fine), and not something BUG-12's DOM-level `TypographyFixer` fix could ever
have touched, because it's about the size of the *viewport itself*, not what
renders inside it.

**Cause.** `ReadAlongBar`'s own doc comment already recorded that this class of
bug had been hit once before, one level down: "notes that appear and disappear
here… changed the bar's height and shifted the whole page under the reader on
every tap." This is the same bug at the level up. The bar's *presence* — not its
already-fixed-height content — was gated on `readAlong.available`, which only
becomes true once `PlayerConnection` finishes an actual Media3 session IPC round
trip (`connect()` then `prepare()`). That is neither instant nor synchronous with
the very first Compose layout pass. So the sequence on opening a book was: reader
opens with no bottom bar yet → `Scaffold` gives the content `Box` the *full*
height → `EpubNavigatorFragment` attaches and Readium paginates its CSS columns
against that height → a beat later `readAlong.available` flips true → the bar
appears → the content `Box` shrinks to make room for it. Paginated columns do not
repaginate for a height change on their own: `R2WebView.onSizeChanged` (decompiled
alongside BUG-12's investigation) only recomputes anything when `w != oldw` --
width, never height. The page kept the vertical layout it had already committed
to against a viewport that no longer existed, while the *visible* portion was now
whatever the shrunk box happened to show of it. Full-screen mode has no bar to
wait for, so it never hits this. Scroll mode has no paginated-column concept to
go stale in the first place.

**Fix**, both in `ReaderScreen.kt`:

1. The bottom bar's Scaffold slot is now gated on `book?.isPlayable` (Room,
   effectively instant) instead of `readAlong.available` (a real IPC round trip).
   Not a full elimination of the race — `book` itself is still one async Room
   emission away from `null` on the very first frame — but it shrinks the window
   from "however long connecting to a media session takes" to "however long one
   local database read takes," which in practice is the difference between a gap
   that reliably gets hit and one that's very unlikely to be.
2. Defensive second layer: the effect that pushes preferences to the navigator is
   now also keyed on `book?.isPlayable`, so if the bar's presence changes for any
   reason after all, preferences get re-submitted — which is what actually asks
   Readium to relayout against the fragment's current size — rather than nothing
   noticing.

**Update, same day.** Reported still not fixed — but with a genuinely useful new
data point: correct on a Chromebook, still broken on the phone that filed the
original report. That is consistent with a race rather than a contradiction of
one: different hardware, different timing, and a Chromebook (ARC++) very
plausibly finishes the same Media3 connection before the phone would have. Fix
1 above only *narrows* the window by starting from better data; it does not
close it, since `book` itself is still one Room emission away from `null` on
the very first frame. Added the actual general-purpose fix instead of a third
attempt at narrowing: `AndroidFragment`'s `Modifier.onSizeChanged` in
`ReaderScreen.kt` now watches the fragment's own measured height and
re-submits preferences on *any* real change, for any reason -- not only the one
BUG-13 originally chased down. That is what should have been reached for from
the start; gating on `book?.isPlayable` reduces how often the underlying gap
gets hit, but reacting to the fragment's actual size is what closes it.

**Update, same day, the actual root cause.** Still not fixed, but this time with
the single most useful report in the whole investigation: tapping the *blank
space above the text* highlighted and opened the word menu for a word rendered
well below it -- "as if the first few lines are duplicated in that space but not
visible." That is not a description a CSS margin or a layout-timing race can
produce. A margin only moves where things are *painted*; it has no way to move
where a tap coordinate resolves to independently of that. Two different things
disagreeing about where the content actually is does.

Confirmed by decompiling one layer deeper than BUG-12/13 had gone:
`R2EpubPageFragment`, the fragment `EpubNavigatorFragment` delegates to for each
chapter's actual `WebView` -- invisible from `EpubNavigatorFragment`'s own public
surface, only visible by reading Readium's internals directly:

```java
private final boolean getShouldApplyInsetsPadding() {
    ...
    if (shouldApplyInsetsPadding != null) return shouldApplyInsetsPadding.booleanValue();
    ...
    return true;   // the default when the app never sets it -- which this app never did
}
```

When true (always, until now), `R2EpubPageFragment` attaches its *own*
`ViewCompat.OnApplyWindowInsetsListener` and applies real Android
`View.setPadding` for system-bar insets on its container -- entirely
independent of, and in addition to, whatever this app's own edge-to-edge
`Scaffold` already reserved for those exact same insets. Two consequences,
matching all four symptoms across BUG-12 and BUG-13 at once:

- **Genuinely double-counted space**, not a CSS margin: the blank gap above the
  text, present in paginated mode with the toolbars visible.
- **The tap coordinate mismatch**, which is what actually pinned this down: real
  `View` padding shifts where content is *painted* without this app's own tap
  math (`event.point.x / density` in the `InputListener`) knowing to subtract
  that same offset, so a tap landing in the padded dead zone gets handed to
  `caretRangeFromPoint` as a content-relative coordinate that is too far down by
  exactly the padding amount -- resolving to whatever text is actually rendered
  that much lower. Exactly "duplicated but not visible."

Fullscreen mode was never affected because it hides system bars entirely --
nothing left for Readium's own listener to pad for, so its independent
computation comes out to zero and nothing doubles up.

**Fix** `shouldApplyInsetsPadding = false` in the `EpubNavigatorFragment.Configuration`
built in `ReaderScreen.kt`. This app's `Scaffold` (`MainActivity`'s
`enableEdgeToEdge()`, the conditional `topBar`/`bottomBar`, `contentWindowInsets`
for fullscreen) already owns every inset that matters here; Readium no longer
gets a second, uncoordinated say over the same space.

### BUG-12, the actual root cause — Readium's own default stylesheet never loads for a real book

**Reported** 2026-08-18, after the previous two rounds shipped. **Fixed**
2026-08-18, same day. **Verification status: better than before, but still not
seen rendering** — see the note at the end.

**Symptom**, on the user's real uploaded book: still a large top margin ("text
starts like 5 rows below the menu"), still no paragraph indent, despite the
previous two rounds of `RsProperties` tuning (`flowSpacing`, `paraSpacing`,
`paraIndent`) targeting exactly this.

**Cause — found by decompiling `readium-navigator-3.3.0-runtime.jar` with jadx
and reading the actual bytecode-derived source, not by inspecting bundled assets
or guessing at CSS properties.** That distinction matters: the two previous
rounds were each individually well-evidenced (a real CSS rule, checked directly)
but wrong about *whether that rule ever ran*. The decompiled
`ReadiumCss.injectHtml` / `injectStyles` shows:

```kotlin
private final void injectStyles(StringBuilder content) {
    boolean hasStyles = hasStyles(content);   // has the page's own <link>, style=, or <style>?
    ...
    if (!hasStyles) {
        add(stylesheetLink(getDefaultCss()));   // ReadiumCSS-default.css
    }
    ...
    add(stylesheetLink(getAfterCss()));         // ReadiumCSS-after.css -- always linked
}
```

`ReadiumCSS-default.css` is the *only* stylesheet Readium ships that defines
`--RS__flowSpacing` / `--RS__paraIndent` and the rules that consume them
(`h1 { margin-top: calc(var(--RS__flowSpacing) * 2) }`, `p { text-indent:
var(--RS__paraIndent) }`). It is linked **only when the page has no CSS of its
own** — and virtually every real epub has its own CSS, so for a typical book this
never happens: the stylesheet those two rounds were tuning never loaded, so
every value fed to it landed on nothing. `ReadiumCSS-after.css` — which is what
BUG-12's original width fix (`maxLineLength`, `pageGutter`) depends on — *is*
linked unconditionally, which is exactly why that fix held while these two did
not: same mechanism suspected all along, finally confirmed by reading the code
that actually decides it rather than inferring it from which files exist.

`publisherStyles` (forced off for every theme in the immediately preceding round,
on the theory that it was the mechanism letting a book's own CSS win) turned out
to be an unrelated dead end for this specific bug: grepping every decompiled
class in the module for `publisherStyles` turns up nothing anywhere near
`injectHtml` or `hasStyles`. **Reverted** to its original, narrower, actually-
justified scope (off only for the Paper theme, to stop a publisher `background`
color from punching through the e-ink palette).

**Fix — bypass the stylesheet pipeline entirely rather than fight it.**
[`TypographyFixer.kt`](android/app/src/main/java/dev/reedd/ui/reader/TypographyFixer.kt)
runs a small script through the same `evaluateJavascript` mechanism
`TapTextResolver` already uses for word-tap, setting `text-indent` on every `<p>`/
`<li>` and tuned `margin-top`/`margin-bottom` on headings and the very first
element in the book, as **inline styles with `!important`**. That specific
combination is deliberate: an inline style with `!important` has the highest
priority CSS defines, full stop — it wins regardless of what selector specificity
or `!important` usage the book's own stylesheet happens to use, which a `<style>`
tag (however `!important`) cannot guarantee. Wired into `ReaderScreen.kt` as a
`LaunchedEffect` keyed on the current resource's `href`, since Readium loads a
new HTML document per chapter and the fix has to be redone for whatever just
loaded.

**Also added: `WebView.setWebContentsDebuggingEnabled(true)` in debug builds**
(`ReeddApp.onCreate`), specifically because of how this investigation went —
three rounds of static analysis to find something `chrome://inspect` on a real
device would have shown directly in minutes. Worth using on the next report:
plug the phone in, open `chrome://inspect` on the connected computer, and the
reader's live DOM and computed styles are right there.

**Why this is not marked fully fixed.** Everything above is now verified by
reading Readium's actual decompiled logic rather than inspecting shipped assets
or inferring behaviour — meaningfully stronger evidence than either previous
round had. But it is still evidence gathered without watching the fix run on the
reporting device, because no emulator runs on this machine and the specific book
was not available to load here. The inline-style-`!important` mechanism is
correct CSS regardless of any Readium version quirk, so this should hold — but
"should hold" is exactly what the previous two rounds also were.

**Update, same day.** Confirmed on device (indirectly, through the next report):
margin and indent were unaffected by this round, but the text-size slider now
visibly changed line spacing without changing glyph size. That is the same class
of bug caught late: `--USER__fontSize` (Readium's own scaling, applied at
`:root`) only works by *inheritance*, and a book whose own CSS gives `<p>` an
**absolute** font size (`12pt`, `16px` — extremely common, especially in books
converted from another format) breaks that inheritance outright; an absolute
unit does not recompute from an ancestor's font-size no matter what `:root` is
told. `TypographyFixer` now forces `font-size` the same way it forces indent and
margin — an absolute pixel value, inline, `!important`, on every text container
and each heading tag at its own scale (h1 largest down to h6), driven by
`ReaderSettings.fontSize` and passed in as a parameter rather than left for the
script to guess. The `LaunchedEffect` calling it is now also keyed on
`settings.fontSize`, not only the chapter `href`, so moving the slider reapplies
immediately to whatever page is already open rather than waiting for the next
page turn.

### BUG-12, continued again — a heading's own margin, and a lost paragraph indent

**Reported** 2026-08-17, after the previous continuation shipped. **Fixed**
2026-08-17, same day. **Unverified on a device**, same caveat as before.

**Symptom** "the top margin is still too wide vertically. its taking up too many
rows. the text starts like 5 rows below the menu at the top." Also requested:
indent the first word of every paragraph.

**Cause, top margin — found directly in Readium's own CSS this time, not
inferred.** `h1 { margin-top: calc(var(--RS__flowSpacing) * 2); margin-bottom:
calc(var(--RS__flowSpacing) * 2); ...; text-align: center }`
(`ReadiumCSS-default.css`). Turning off publisher styles (the previous fix) does
not touch this — it is Readium's *own* rule, always active. `flowSpacing`'s
default is `1.5rem`, so a book that opens on a chapter heading, the ordinary case,
got `3rem` of margin above the title before a single line of body text — the "5
rows." The app had already set `flowSpacing` down to `0.5rem` for a different
reason (BUGS.md's original margin work), which helped but left `1rem` still, and
nobody had connected that value specifically to the heading rule's `× 2`.

**Cause, missing paragraph indent.** Also in the same stylesheet: `p {
text-indent: var(--RS__paraIndent) }`, unconditional, not gated behind
`publisherStyles` or any preference. Its own default is `1em`, an ordinary
first-line indent. It was rendering with none anyway, the whole time this app has
existed, because `RsProperties.paraIndent` was simply never set — Readium's 1em
default was never actually reaching the page.

**Fix**, both in the same `RsProperties(...)` call (`ReaderScreen.kt`):

- `flowSpacing` down to `0.25rem` (from `0.5rem`), so the heading rule's `× 2`
  comes to `0.5rem` — real spacing, not the multi-row gap.
- `paraSpacing` pinned explicitly to `0` — matches Readium's own default for a
  plain paragraph, set anyway rather than left to chance now that this class's
  unstated defaults have twice turned out to matter.
- `paraIndent` set to `1em`, restoring the standard first-line indent Readium's
  own default already called for but this app had never actually supplied.

### BUG-12, continued — the width fix was not the whole story

**Reported** 2026-08-17, after BUG-12 shipped. **Fixed** 2026-08-17, same day.
**Unverified on a device**, more than usually so this time — see below.

**Symptom** on a real uploaded book (not `sample-short.epub`): text still sat with
large top and bottom margins, appearing to start partway down the screen rather
than at the top. Separately, at the smallest font-size setting the text did not
get visibly smaller than one step above it — "the words just seemed to move closer
together" instead.

**Cause — a different mechanism than BUG-12, this time book-specific.** BUG-12's
`maxLineLength` fix was real and correct, but only for what it targeted: Readium's
*own* forced layout rules (column width, gutter). It does nothing about the
*publisher's own CSS*, which was still switched on for every theme except Paper.
Checked directly this time rather than guessed at: every stylesheet Readium ships
(`readium-navigator-3.3.0.aar`, all three of `ReadiumCSS-{default,before,after}.css`
across its horizontal/rtl/cjk variants) was grepped for `align-items`,
`justify-content`, `vertical-align`, `display:flex`, `display:table*` — none of
them appear anywhere. Readium's own CSS does not center content vertically. A book
that renders centered is a book whose *own* stylesheet is doing it — a
fixed-height wrapper, a leftover title-page layout applied to the whole book,
whatever the source epub happens to contain — reaching through because
`publisherStyles` was still unset (Readium's default: on) for anything but the
Paper theme. The font-size symptom fits the same cause: publisher CSS commonly
hardcodes `px` font sizes on specific elements, which do not respond to
`--USER__fontSize`'s `rem`-based scaling at all, so only the elements *without*
their own explicit size actually shrink -- looking exactly like "one step doesn't
do much."

**Fix** `publisherStyles = false` unconditionally in
[`ReaderViewModel.preferences()`](android/app/src/main/java/dev/reedd/ui/reader/ReaderViewModel.kt) —
previously only forced off for the Paper theme (to stop a publisher `background`
color punching through the e-ink palette), now off for every theme. Every book now
renders with Readium's own layout and its own `rem`-based font scaling exclusively,
regardless of what the source epub's stylesheet says. The trade-off is explicit: a
book's own typography (custom fonts, non-bold/italic emphasis styling) is lost
everywhere, not just in Paper, in exchange for every book filling the screen and
responding to the font-size setting the same way.

**Why "unverified" carries more weight here than usual.** The vertical-centering
diagnosis rests on ruling out both the CSS Readium ships (checked directly) and
the Compose layout around `AndroidFragment` (read end to end — `Modifier.fillMaxSize()`
reaches it with nothing wrapping it in `Alignment.Center`), which leaves the
publisher's own CSS as the remaining explanation by elimination rather than by
observing it directly in the failing book. No emulator runs on this machine, and
the specific epub that reproduced this was not available to inspect. If turning
publisher styles off does not fully resolve it, the next place to look is whatever
that book's own stylesheet actually contains — worth attaching it (or its OPF/CSS)
to the next report rather than only a description, so the actual rule can be found
instead of inferred.

### BUG-11 — Reopening a book landed on page one instead of the saved position

**Reported** 2026-08-17, immediately after BUG-10's fix. **Fixed** 2026-08-17.
**Severity** high. **Unverified on a device**, like the rest of playback.

**Symptom** "when i play a book then pause it, if i go back to the main screen
and select another book and then go back to the main screen and select the
previous book, the screen wakes up at the beginning of the book even though the
cursor is on another page."

**Cause — plausible, not device-confirmed; a race BUG-10's fix made possible
rather than one it introduced by itself.** `ReaderViewModel` correctly saves and
restores the reading position (`readingLocator`) independently of audio, and
that part is untouched. What changed is that `PlayerConnection` is now one
connection shared app-wide (BUG-10), so reopening book A after visiting book B
calls `prepare()` on a controller that a moment ago belonged to a *different*
book, and `prepare()` is asynchronous — a `MediaController` talks to its session
over an IPC round trip, even in-process. [`ReadAlongViewModel`](android/app/src/main/java/dev/reedd/ui/reader/ReadAlongViewModel.kt)'s
poll loop starts immediately after, and its very first tick could read
`player.currentPositionMs()` before that round trip lands -- plausibly still
book B's position, or 0. That reading was trusted exactly like a real sentence
change: `FollowController.onSentenceChanged` returns true for the first index
it is ever given (there is nothing yet to compare it to), so the read-along
layer would issue `fragment.go(locator)` to whatever sentence a stale read
happened to map to -- typically the very first one -- **immediately after** the
reader had already opened at the correct saved locator, visually overwriting it.
"The cursor is on another page" was accurate; a race one layer up moved off it a
moment later.

**Fix** two changes, both in `ReadAlongViewModel`, working together:

1. `start()` now seeds the read-along position from `book.playbackPositionMs` --
   the value just handed to `prepare()`, known good by construction -- rather
   than from any read of the player. `follower.onNavigated(seedIndex)` records
   it without emitting a navigation, so the reader is not asked to move from
   wherever it is already sitting.
2. The poll loop's first tick no longer acts on what it reads, regardless of
   the value: it exists only to give the controller's state time to catch up.
   Every tick from the second one on works exactly as before. Even in a worse
   case where settling takes longer than one tick (100-400 ms), the loop
   self-corrects on the next real reading rather than getting stuck.

Together these mean the earliest a stale read could possibly cause a visible
jump is push back by a full poll interval, and the baseline it would be judged
against is the *known-correct* position rather than nothing -- both taken
together should be well clear of realistic settling time on this connection.

### BUG-10 — Two books' audio got tangled together

**Reported** 2026-08-17. **Fixed** 2026-08-17. **Severity** high — playback is the
core of Phase 4.

**Symptom** "if i'm playing audio from one of the books and click the back button,
the audio keeps playing but i can't visually see which book is being played. if i
then select the other book, the first book keeps playing and now everything is
mixed up." Also: opening a book should never start playing it by itself.

**Cause — confirmed.** [`PlayerConnection`](android/app/src/main/java/dev/reedd/playback/PlayerConnection.kt),
the app's handle on the one shared `PlaybackService`, was built fresh inside
`ReadAlongViewModel.factory` every time the reader opened, and released again in
that ViewModel's `onCleared` when it closed. Two consequences, both from the same
mistake:

1. **No memory of what was playing.** A brand-new `PlayerConnection`'s state
   always starts blank. Leaving the reader released the connection entirely, so
   nothing anywhere in the app retained "book A is playing" -- there was nothing
   for a library screen to show, and no way to tell.
2. **The guard against reloading a book compared against that same blank
   slate.** `prepare()` skipped reloading only when its *own* cached `bookId`
   already matched -- which, for a freshly-built connection, was never true, even
   when the book being opened was the one already playing. So opening book B
   always called `setMediaItem` + `prepare()` on the *shared* player. That much
   was actually correct -- Media3 sessions have exactly one player, so this does
   replace what is loaded. What it did not do was touch `playWhenReady`, which is
   a **player-level** flag, not a property of the media item, and survives
   `setMediaItem` by design. If book A was playing, `playWhenReady` was already
   `true`; book B's new item then started itself the moment it finished
   buffering, with no `.play()` call anywhere. That is "the first book keeps
   playing" (it audibly did, until B's buffering caught up) and "opening a book
   auto-plays it", from the same root cause.

**Fix**

1. `PlayerConnection` is now a single instance shared by every screen
   (`AppContainer.playerConnection`), never released. `ReadAlongViewModel` no
   longer builds or releases one, and no longer overrides `onCleared` for this --
   its `viewModelScope` still gets cancelled as normal, which is what actually
   stops that book's own position-poll loop.
2. `prepare()`'s guard now checks `controller.currentMediaItem?.mediaId`, the
   controller's real current item, instead of a copy of its own that a fresh
   instance could never have agreed with anyway.
3. Switching to a genuinely different book now calls `controller.pause()` before
   `setMediaItem`, so the outgoing book's `playWhenReady` can never carry over.
   Reopening the book already loaded skips this entirely (the guard above), so it
   is not disturbed if it happens to be playing.
4. The library screen now observes the same shared connection: a book's status
   badge becomes "Playing"/"Paused" while it is the current item, and a
   persistent mini-player bar (play/pause, tap to reopen) appears at the bottom
   whenever anything is loaded -- which is what makes "keeps playing but I can't
   see which book" answerable at all.

**Unverified.** Like the rest of playback (README, "Known gaps"), this has not run
on a device -- the emulator does not work on this machine. What specifically needs
checking: that switching books really does cut the first one off promptly rather
than overlapping for a moment while the new item buffers, and that the mini-player
bar's state stays in sync through backgrounding and a locked screen.

### BUG-9 — Word tap still missed some words, and sometimes fired on blank space

**Reported** 2026-08-17, after BUG-8's fix. **Fixed** 2026-08-17. **Severity** high.

**Symptom** "some of the words i click on doesn't work. also there are sometimes i
click on blank space and it launches the menu. i clicked on a blank space on the
second page and when i selected definition... it brought up a word from the first
page."

**Cause — three separate, independently-confirmed bugs, not one:**

1. **Word resolution only ever ran in the middle third of the screen.** The tap
   handler kept the left/right thirds reserved for page-turning and bailed out of
   word resolution entirely for any tap outside the middle — but text runs the full
   width of a line, so roughly two out of three taps on an actual word landed in a
   "page-turn" zone and never even asked the WebView what was there. Systematic, not
   random, though it looks random from where a reader is tapping on any given line.
2. **`caretRangeFromPoint` reports the *nearest* caret, not "nothing here."** It is
   specified to always return the closest text position to the given point, even when
   that point is over blank space, a margin, or -- in a paginated, CSS-column layout
   -- a position that belongs to a *different page*. The resolver trusted whatever it
   returned unconditionally. This is exactly "blank space opens the menu" and "page 2
   resolved to a page-1 word": both are the same nearest-neighbour fallback, just at
   different distances.
3. **The currently-spoken-sentence highlight sat on top of its own text and
   intercepted the hit-test.** Readium's decoration overlays (`id="r2-decoration-N"`)
   are real elements painted above the text with no `pointer-events` rule anywhere in
   Readium's bundled JS, confirmed by extracting and grepping
   `readium-navigator-3.3.0.aar`. `caretRangeFromPoint` hit-tests paint order, so a
   tap on whichever sentence happened to be highlighted at that moment landed on the
   decoration `<div>`, not the text node beneath it, and resolved to nothing.

**Fix**

1. `ReaderScreen.kt`'s tap handler now tries to resolve a word *first*, anywhere on
   the page, and only falls back to a page-turn zone (now called explicitly via
   `fragment.goBackward()`/`goForward()`, since the fallback decision has to happen
   after the async resolution, not before it) when nothing was actually under the
   finger. A tap that lands on a word right at the screen edge now opens the menu
   instead of turning the page -- the same trade-off Kindle and most other readers
   make when the two gestures would otherwise collide on the same pixels.
2. `TapTextResolver`'s script now rejects the resolved word unless the tap point
   falls within its own rendered `getBoundingClientRect()` (6px tolerance for
   font-metric rounding). A "nearest" match hundreds of pixels away -- blank space,
   or a different page -- is now treated as a genuine miss.
3. The same script injects one `<style>` rule, once, before its first
   `caretRangeFromPoint` call: `[id^="r2-decoration-"] { pointer-events: none; }`.
   Purely a hit-testing change -- the highlight still renders identically -- so it no
   longer shadows the text it's drawn over.

**Update, same day.** Fix 1's page-turn zone (`goBackward`/`goForward`) was removed
again immediately after, by request — tap is now word-resolution only, full stop, and
paging is swipe-only. The tolerance check and the decoration pass-through (2 and 3)
are what actually matter for word tap and are unaffected. The temporary tap-diagnostic
snackbars ("No word found at...", "Reader ready: tap a word") were also removed once
tapping was confirmed working — that was always their stated purpose, see the original
commit that added them.

### BUG-8 — Word tap only worked on a couple of words

**Reported** 2026-08-17. **Fixed** 2026-08-17. **Severity** high — this is E-7's whole
feature.

**Symptom** "the single tap to highlight a word to bring up the context menu is not
working consistently... i was able to see it on a couple of words."

**Cause — confirmed, a design conflict rather than a bug in either mechanism.** Two
separate tap paths were layered over the same text, left over from two different
rounds of work:

1. **E-6** (2026-08-12) placed an invisible, *activable* `Decoration.Style.Underline`
   over every aligned sentence (`ReadAlongLocators.tapDecorations`, group
   `readalong-taps`), so tapping a sentence would start playback from it. Reported
   through Readium's `DecorableNavigator.Listener`.
2. **E-7** (2026-08-13) added a general-purpose `InputListener.onTap`
   (`ReaderScreen.kt`) that resolves the tapped word via `caretRangeFromPoint` and
   opens [`WordMenu`](android/app/src/main/java/dev/reedd/ui/reader/WordMenu.kt).

Readium only forwards a tap to `InputListener.onTap` when it does **not** land on an
activable decoration; a tap that hits one is consumed as a decoration activation and
never reaches the second listener. Since aligned sentences cover most of a page (11 of
12 on the sample book), nearly every tap was captured by mechanism 1 and silently
started playback instead of opening the word menu — word tap "worked" only on the rare
word sitting in unaligned text, which has no covering decoration. E-6's own note even
says it avoided "the invisible tappable-decoration layer... the mechanism that caused
BUG-3," but the code implementing it was never removed when E-7 replaced it with a
better design, so the two kept fighting over every tap.

**Fix** deleted mechanism 1 entirely: `ReadAlongLocators.tapDecorations`, `TAP_GROUP`,
`tapDecorationId`/`chunkIndexFromTapId`, the `DecorableNavigator.Listener` that called
`playFrom` on activation, and the custom `Underline` decoration template that only
existed to keep it invisible (`decorationTemplates()`). Nothing was left to replace it
with, because `WordMenu`'s "Read from here" already plays from the tapped word's
sentence (`ReadAlongViewModel.readFromTappedWord`, matched through the same
`ChunkIndex` used everywhere else) — it was a strict superset of what the decoration
layer did. Every tap in the middle third of the page now has exactly one path.

### BUG-5 — Library card does not show conversion progress until you open the book and come back

**Reported** 2026-08-12. **Fixed** 2026-08-12.

Two bugs compounding, the second hiding the first.

The library's poll loop chose its wait from the *previous* poll's result, so at app
start — when nothing is converting — it slept for the full idle period of 30 seconds.
A book added inside that window was not polled until the sleep expired.

It looked like opening the detail screen fixed it because the nav graph constructed a
**second `LibraryViewModel`** for that destination, and that class starts a polling
loop in its `init`. Visiting the detail screen therefore started another poller whose
first iteration polled immediately. Two loops were hitting the server, and the hidden
one was masking the stalled one.

**Fix** the wait is now interruptible (`withTimeoutOrNull` on a conflated channel) and
uploading nudges it awake, so progress starts immediately; the idle backstop dropped
to 10 s. The detail screen's actions moved onto `BookDetailViewModel`, removing the
duplicate ViewModel, the duplicate loop, and four now-dead methods from
`LibraryViewModel`.

### BUG-6 — App crashes when adding a book, after a bad server address was entered

**Reported** 2026-08-12. **Fixed** 2026-08-12.

Retrofit rejects some base URLs by throwing `IllegalArgumentException`. That is not an
`IOException`, so it went straight past every caller's error handling and killed the
app — a typo in Settings was enough.

**Fix** `ApiProvider` converts it to `ServerNotConfigured`, which is what it means to
the user, and `importAndUpload` now catches `Throwable` rather than `Exception` (still
re-throwing `CancellationException`) so an `Error` from Readium or a bitmap decode
shows a message instead of taking the app down. A regression test covers five hostile
addresses.

The crash reporting added alongside this is what would have diagnosed it in one step:
see "If the app crashed" above.

### BUG-7 — Reader settings need the book closed and reopened to take effect

**Reported** 2026-08-12. **Fixed** 2026-08-12.

Changing font size or theme did nothing until the reader was left and re-entered.

Preferences were being submitted inside `AndroidFragment`'s update lambda, which runs
when the fragment is **created**, not on every recomposition — so a changed setting
never reached the WebView.

**Fix** a `LaunchedEffect` keyed on the preferences pushes them as they change. Pushed
rather than recreating the fragment, which would lose the reading position.

---

### BUG-1 — Audio controls sit under the Android navigation bar

**Severity** high — the transport controls are partly unusable. **FIXED**, untested
on device.

**Symptom** "the audio controls at the bottom of the screen are blocked by the
navigation icons for the android phone".

**Cause — confirmed by inspection.** `MainActivity` calls `enableEdgeToEdge()`
(`android/app/src/main/java/dev/reedd/MainActivity.kt:18`), so the app draws behind
the system bars. Nothing anywhere in the app then applies window insets — a search
for `navigationBarsPadding`, `windowInsetsPadding`, `systemBars`,
`contentWindowInsets` and `safeDrawing` across `app/src/main/java/dev/reedd/`
returns **no matches**. Material 3's `Scaffold` insets its *content* slot but places
`bottomBar` flush against the bottom edge and leaves its insets to the caller, so
`ReadAlongBar` is drawn underneath the navigation bar.

This is not phone-specific and would reproduce on any device with on-screen
navigation. It was invisible in development because nothing had ever been rendered
on a device.

**Fix applied** `Modifier.navigationBarsPadding()` on the `Surface` in
`ui/reader/ReadAlongBar.kt`.

The other screens were left alone deliberately. Material 3's `TopAppBar` insets
itself by default (`TopAppBarDefaults.windowInsets`), and `Scaffold` passes system-bar
insets to its content slot, which every screen already applies via `padding(padding)`
— and the device test showed no problem at the top of the screen, which is
corroborating evidence. The custom bottom bar was the one component taking
responsibility for its own insets and not doing it.

---

### BUG-3 — Every line is highlighted or underlined

**Severity** high — makes read-along useless and hides BUG-2. **FIXED**, untested on
device.

**Symptom** "every line in the text is highlighted or underlined depending on the
background theme".

**Cause — confirmed, and it is the invisible tap layer.** To make "tap a sentence to
play from there" work, the app applies a *second* group of decorations covering
**every aligned sentence in the current resource**, meant to be invisible and to
exist only to catch taps:

```kotlin
style = Decoration.Style.Highlight(tint = TRANSPARENT, isActive = true)  // TRANSPARENT = 0
```

The assumption was that `tint = 0` renders nothing. It does not, and the mechanism is
visible in Readium's API:

```
HtmlDecorationTemplate.Companion.highlight(defaultTint: Int, lineWeight: Int,
                                           cornerRadius: Int, alpha: Double, ...)
```

**The template supplies the opacity, not the tint's alpha channel.** So a
fully-transparent `0x00000000` tint is rendered as `rgba(0, 0, 0, templateAlpha)` — a
visible band on every sentence, appearing as a highlight or an underline depending on
what it is drawn over. That matches the symptom exactly, including varying with the
theme.

The intended single highlight (`Style.Highlight` at 35 % of the primary colour, in
group `readalong`) is a different code path and may well have been working all along —
it simply could not be seen underneath the tap layer.

**Fix applied — a separate style class with its own template.** Decoration templates
are keyed by *style class*, not by group, so an invisible `Highlight` template would
have blanked the real highlight too. The tap layer therefore now uses
`Decoration.Style.Underline` — which is also `Tinted` and `Activable`, so it is still
tappable — and a template is registered for `Underline` that draws nothing:

```kotlin
HtmlDecorationTemplates.defaultTemplates().apply {
    set(Decoration.Style.Underline::class, HtmlDecorationTemplate(
        layout = BOXES, width = WRAP,
        element = { """<div class="readalong-tap-target"></div>""" },
        stylesheet = ".readalong-tap-target { background-color: transparent; border: none; ... }",
    ))
}
```

Two details that matter: it builds from `defaultTemplates()` rather than an empty set,
or the default `Highlight` template would be dropped along with everything else; and
the element still exists and covers the text (`BOXES` gives one box per line
fragment), because with no element there would be nothing to tap. The templates are
passed to `createFragmentFactory(configuration = EpubNavigatorFragment.Configuration(...))`,
without which the override never reaches Readium.

`Highlight` is untouched, so the spoken sentence keeps Readium's normal theme-aware
highlight.

**If it is still visible on re-test,** the fallback is to stop applying the tap layer
altogether (one line, loses tap-to-play but definitively fixes the display), or to
switch tap-to-play to `SelectableNavigator.currentSelection()` and match the selected
text against the chunk list — which the existing `TextNormalizer` already makes easy.

---

---

## Not fixing / by design

- **The first spoken line does not highlight.** audiblez prepends
  `"<title> – <author>."` to chapter 1 and it exists nowhere in the epub, so it
  cannot be located on the page. Documented in `audiblez/SYNC.md`; on
  `sample-short.epub` 11 of 12 sentences align and that injected line is the only
  miss.
- **A job disappearing from the server after download.** The app deletes a finished
  job once both files are on the device, because the server has no cleanup policy of
  its own. Toggleable in Settings.

---

## Unverified

Behaviour that has never been confirmed on hardware, so it is neither working nor
known broken. The emulator does not run on this machine (the SDK's bundled
`qemu-system-x86_64-headless` segfaults ~20 s into boot under every GPU mode,
including with a stock AVD), so a real device is the only way to settle these.

- Whether Readium's JavaScript resolves the read-along text anchors to the ranges the
  aligner intends — the alignment itself is tested, the rendering of it is not.
- Whether the tap-to-text coordinate conversion is right. Readium reports taps in view
  pixels and the WebView's JS works in CSS pixels, so the app divides by display
  density; that is inference, not documentation. The "Read from here" card shows the
  matched sentence precisely so this is self-diagnosing.
- Whether **Read from here** appears in the long-press selection toolbar at all.
- Whether the AAC priming offset is needed (the nudge exists, defaulted to 0).
- Background playback, lockscreen controls, and download resume on a real network.
