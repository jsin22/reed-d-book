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

**Getting the build number** — the APK's timestamp is enough:

```sh
stat -c '%y' android/app/build/outputs/apk/debug/app-debug.apk
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
