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
2. **ONNX Runtime.** Written when Kokoro was the engine, which ships ONNX exports;
   ONNX Runtime with a DirectML or ROCm provider would have avoided the
   torch/ROCm problem entirely. Pocket TTS is the engine now and its ONNX-export
   situation hasn't been checked, so this route needs re-evaluating before
   pursuing it.
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


### BUG-23 — A pasted API token containing a newline crashed the app on every launch

**Reported** 2026-08-24 -- "i added the api token, tried uploading a book and
the app crashed", then "closed the app and opened it back up and it crashed
automatically" on every subsequent launch, even after clearing app storage
and re-adding the token. **Fixed** 2026-08-24. **Severity** critical -- total
loss of app function for anyone whose token has this shape, and it silently
defeats the app's own crash-reporting (see below), which made this by far the
hardest bug in the project to get a stack trace for.

**Symptom.** App unusable: crashed immediately on every launch once a
per-user token was saved (see the multi-user sharing feature). No crash
report ever reached the server (`GET /api/diagnostics/crashes`) despite
several rounds of hardening `CrashLog`/`CrashReporter` to send earlier and
more aggressively. Eventually diagnosed from the phone's own OS-level crash
capture (OPPO/ColorOS `crashbox`), pulled off-device by hand once adb/USB
debugging turned out not to be available:

```
java.lang.IllegalArgumentException: Unexpected char 0x0a at 50 in Authorization value
    at okhttp3.internal._HeadersCommonKt.headersCheckValue(-HeadersCommon.kt:157)
    at dev.reedd.data.remote.AuthInterceptor.intercept(AuthInterceptor.kt:23)
    at okhttp3.internal.connection.RealCall$AsyncCall.run(RealCall.kt:580)
    at java.lang.Thread.run(Thread.java:1572)
```

**Cause.** The pasted token contained an embedded newline (`0x0a`) --
almost certainly picked up from copying it out of a chat bubble or terminal
block whose selection included a trailing line break. `SettingsStore.
setServer()` already called `token.trim()`, which strips *leading/trailing*
whitespace, but not one embedded mid-string by however the paste actually
landed in a Compose `OutlinedTextField(singleLine = true)` -- `singleLine`
does not reliably strip an embedded `\n` from pasted text, only from typed
input. `AuthInterceptor` then built `"Bearer $value"` and handed it to
OkHttp's `Request.Builder.header()`, which validates header values per RFC
and throws `IllegalArgumentException` on any raw control character -- a real
safety check (this is exactly what stops header/response-splitting
attacks), just not one anything downstream was prepared for.

The exception was **thrown on OkHttp's own async dispatcher thread**
(`RealCall$AsyncCall.run` → `Thread.run`), never on the calling coroutine at
all -- so it never reached any of `LibraryViewModel`'s `runCatching` blocks,
and crashed the whole process via the JVM's default uncaught-exception
handler on every request the app made, starting from the very first one at
launch (`ConversionWatcher.reconcile()` polling the server). This is also
why no crash report ever reached the server: `CrashLog.upload()` and the
`sendPendingEarly()` hardening added while chasing this bug both send the
report through the *same* `Authorization` header, so every report-send
attempt failed (or crashed) the same way the original request did.

**Fix.** Strip all whitespace from the token, not just the edges, in two
places: `SettingsStore.setServer()` (fixes it at the source, so a bad paste
is never saved), and `AuthInterceptor.intercept()` itself (defense in depth,
and critically it *self-heals* a token already saved bad -- no need to
re-enter it in Settings after updating). `CrashReporter.postPlain()`'s early
-send path got the same filter for consistency. Confirmed against the
device's own crash captures: the same `IllegalArgumentException` at
`AuthInterceptor.kt:23` appears identically across five separate process
launches (five different PIDs) in the captured `crash_log`, i.e. it really
was 100% reproducible from the moment the bad token was saved, not
intermittent.

**Lesson for next time.** Whatever crash-reporting exists must not itself
depend on the exact thing most likely to be broken (here: a valid,
correctly-encoded auth token) -- `sendPendingEarly()`'s early-send hardening
was the right instinct but could not have worked while the auth header
itself was what crashed every request, including its own. A future
diagnostic path (or the crash reporter itself) should have a way to reach
the server with **no** headers at all as a last resort.

### BUG-22 — A dialogue-heavy chapter could stall for 20+ minutes before synthesis even started

**Reported** 2026-08-20 -- as a re-test of BUG-21's fix ("just kicked off
another book to test. verify its working"), which surfaced this instead: the
same book stuck at 0% for 90+ seconds looked identical to BUG-21 at first, but
turned out to be a completely different, far more serious bug hiding behind
it. **Fixed** 2026-08-20. **Severity** high -- not cosmetic like BUG-21, this
was real, massive, silent wall-clock time.

**Symptom.** Progress and ETA genuinely frozen (not just stale, as in
BUG-21) for well over 90 seconds on `a-scandal-in-bohemia.epub`,
`pocket_tts`. Diagnosed from process-level evidence: the celery worker was
at 70%+ CPU the whole time but `nvidia-smi` showed 0% GPU utilization (`py-spy`
was tried for a live stack trace but this machine has no root, and it needs
ptrace permission it does not have; `/proc/<pid>/task/*/wchan` substituted --
the main thread showed as actively running, not blocked, ruling out a
deadlock) -- and `worker.log` showed the tiny cached chapter 1 finishing in
1.44s followed by *nothing at all* for chapter 2, not even the "starting timer
now!" line `_generate_audio_stream_short_text` logs before any actual
generation. Whatever was consuming that CPU was happening *before*
`engine.synthesize()` was ever called for chapter 2's first sentence -- which,
combined with a plain, no-TTS reproduction script hanging on nothing but epub
parsing and sentence splitting, pointed at `quote_split`/`text_split` rather
than Pocket TTS itself.

**Cause, confirmed by direct measurement.** `text_split.py`'s
`split_sentences()` calls `spacy.load('xx_ent_wiki_sm')` **fresh from disk on
every single call** -- never cached. Measured a single *cold* call at 2.6
seconds. `quote_split.py`'s `split_into_spans()` -- which every chapter's
synthesis goes through, called once at the top of `core.py`'s
`gen_audio_segments()`, before its own per-sentence loop even begins -- calls
`split_sentences()` once per narration/quote span, not once per chapter.
Doyle's dialogue-heavy prose in this specific chapter has 268 quote spans,
~536 total narration+quote spans once narration in between is counted: a
naive 536 x 2.6s puts the upper bound around 23 minutes of pure, repeated
model-reloading, invisible to any progress or ETA tracking because it all
happens before `gen_audio_segments()`'s counted work starts. (The real
figure was lower in practice -- see Verified below -- almost certainly
because the OS's file cache warms up after the first load, so most of the 536
were cheaper than that one cold measurement, not free.) This is not new, not
related to any change this session -- almost certainly been quietly costing
every dialogue-heavy book a large multiple of its real synthesis time for as
long as `quote_split.py` has existed, just never isolated and measured
before.

**Fix.** `text_split.py` now loads the spaCy model into a module-level
global once per process, not once per call -- `nlp.add_pipe('sentencizer')`
moved inside the same one-time initialization (calling it twice on a reused
`nlp` would itself raise, since the pipe would already exist). Measured
directly, same chapter, in isolation, before and after: `split_into_spans()`
on this exact 46,586-character chapter went from an untimed multi-minute
stall to a measured 2.04 seconds.

**Verified against real, whole-book conversions**, same book/engine/voice
throughout: the last run *before* this fix (`82ac5f90`, 2026-08-19) took
**10m37s** end to end. Two runs *after* the fix (`7ddf0abd` and `f8cb63b6`,
2026-08-20) took **3m31s** and **3m29s** -- a real ~3x speedup in production,
not just the isolated measurement above.

Unverified: whether any other caller relies on getting a *fresh* spaCy
pipeline per call (none found -- `split_sentences()`'s only other caller,
`literary_analysis.py`, wants the same shared splitter by design, per
`text_split.py`'s own module docstring) -- and whether per-process caching
interacts correctly with `_run_chapters_parallel`'s worker pool, where each
worker process would still pay the one-time load cost independently (expected
and fine -- once per process, not once per span, is the entire fix).

### BUG-21 — Conversion's estimated time was way off, and could stay stuck for minutes

**Reported** 2026-08-19, revised again 2026-08-20 after the first fix did not
fully cover it. **Fixed** 2026-08-20. **Severity** low -- cosmetic, but
actively misleading.

**Symptom, first report:** the ETA read as too optimistic for a whole
`pocket_tts` conversion and never corrected itself. **Symptom, second report:**
"the estimated time is way off. its stuck on 1m 33s and 0% and its been a
couple mins. can't we take the total number of words and divide by estimate
words per second for the model?"

**Cause, first pass.** `core.py`'s `_run_chapters_sequential` (the path every
GPU job uses, since `resolve_worker_count()` falls back to one process
whenever CUDA is available) computed each chapter's real chars/sec
(`chars_per_sec = len(text) / delta_seconds`) but only used it for a log
line -- it never wrote that back into `stats.chars_per_sec`, which the ETA is
computed from. So the ETA stayed pinned to `main()`'s initial guess (500
chars/sec on CUDA, 50 on CPU) for the whole run. Measured Pocket TTS at ~314
chars/sec real GPU throughput -- well under the 500 guess -- so the ETA read
as finishing sooner than it really would. First fix: revise
`stats.chars_per_sec` from real, cumulative throughput after every chapter,
matching what `_run_chapters_parallel` already did.

**Cause, second pass -- the first fix was not granular enough.** That
revision only happens *between chapters*. For a book whose first real chapter
is large (or, as reported, whose actual chapter 1 is a tiny cached
table-of-contents file and the real content is chapter 2), the ETA stays at
the initial guess for as long as that first chapter takes to finish -- which
can be minutes, exactly as reported ("stuck... a couple mins"). The user's own
suggestion (words-so-far / real elapsed time) was the right shape of fix, just
needed applying more often than "once per chapter."

**Fix.** `stats` gained a `start_time`, set once when the whole job starts.
`gen_audio_segments`' existing per-*sentence* progress update now also revises
`stats.chars_per_sec` from `stats.processed_chars / (now - stats.start_time)`
on every sentence (after a 2-second grace period, so one unusually fast or
slow first sentence cannot swing the estimate wildly) -- not just at chapter
boundaries. `_run_chapters_sequential`'s own now-redundant per-chapter
revision was removed; the per-sentence one supersedes it, including on the
chapter's last sentence. `_run_chapters_parallel` is unchanged -- it runs
chapters in separate worker processes with no cheap way to get per-sentence
progress out of them, so chapter-boundary granularity is what it has to work
with, but it never runs on CUDA in practice anyway.

**Cause, third pass -- a second bug in the server, found while verifying the
above.** Testing the fix against a live job showed the worker's own log
printing a fresh, correct ETA every sentence exactly as intended, but
`GET /api/jobs/{id}` -- what the app actually polls -- kept reporting the same
stale one. `server/app/tasks.py`'s `_Progress` (the class that turns audiblez'
`post_event` stream into writes to `job.json`) only wrote to disk when the
whole-number percentage changed, on the reasoning that `CORE_PROGRESS` fires
once per sentence and a novel would otherwise be tens of thousands of writes.
That reasoning held when percentage was the only thing worth refreshing; once
the ETA started changing every sentence too (this same bug, second pass), a
book whose percentage sits at a single value for a long stretch -- 0%, for the
first couple of minutes of a big book -- meant the fresher ETA underneath it
never reached the manifest at all, exactly the second report's "stuck on 1m
33s and 0%."

**Fix.** `_Progress` also writes now if at least `PROGRESS_SAVE_INTERVAL_SECONDS`
(2.0) have passed since its last write, even when the percentage has not moved
-- bounded, time-based freshness instead of relying on percentage alone to
decide when the ETA is worth persisting. Covered by a new test
(`test_a_stuck_percentage_still_refreshes_the_eta_periodically`, mocking
`time.monotonic` rather than sleeping) alongside the existing
`test_repeated_percentages_do_not_rewrite_the_manifest`, which still holds --
14/14 server tests passing.

Verified twice against real conversions (`the-tell-tale-heart.epub`,
`pocket_tts`, `af_heart_clone`) -- once by watching `worker.log`, which caught
the third-pass bug (the log looked correct while the API did not, which is
what pointed at persistence rather than the ETA math itself), and again after
the fix by polling `GET /api/jobs/{id}` directly: progress and ETA now
visibly move every few seconds from the very first sentence, converging
smoothly to 0 rather than sitting frozen until a chapter completes or a
percentage point ticks over.

### BUG-20 — The page count did not change with text size

**Reported** 2026-08-20. **Fixed** 2026-08-20. **Severity** low, but confusing --
made the page indicator look broken.

**Symptom** "as the text size changes the number of pages should change...
currently i'll see page 21 on two pages."

**Cause.** The page indicator (`ReaderViewModel.PageInfo`) was Readium's
`Publication.positions()` -- not real pages at all. Traced the actual formula by
decompiling `readium-streamer`'s `EpubPositionsService` (no public docs on the
exact number): one "position" is **1024 bytes of a chapter's raw XHTML markup**
(`EpubPositionsService.ReflowableStrategy.ArchiveEntryLength`, the library's own
`getRecommended()` default), and the whole-book total is the sum of that across
every chapter. It is a proxy for how far into the book's underlying markup you
are, bucketed into ~1KB chunks -- entirely independent of font size, screen
size, or how the text is actually laid out, which is exactly why resizing text
never moved it: the underlying markup byte count does not change just because
it now takes more physical swipes to read the same amount of it.

**Fix.** Replaced it with Readium's `EpubNavigatorFragment.PaginationListener`
(`onPageChanged(pageIndex, pageCount, locator)`), wired up where the fragment is
created (`ReaderScreen.kt`) and forwarded to `ReaderViewModel.onPageChanged`.
This reports the real, currently-rendered page count for whichever chapter is
loaded, fired again every time the WebView re-paginates -- including on a
font-size change, so the count now genuinely responds to it. Deliberately
**per-chapter, not whole-book**: a true whole-book count would mean laying out
and measuring every chapter at the current font size on every resize, not just
the one on screen, which would make changing text size noticeably slower on a
long book. The label is unchanged ("21 / 340"), but now means "page 21 of this
chapter's 340" rather than a whole-book estimate -- a real, deliberate scope
change discussed with the user before implementing, not a silent one.

Unverified: whether Readium's page index is really 0-based (inferred by
decompiling `EpubNavigatorFragment`'s own internal `PageChangeListener`, which
extends `ViewPager.SimpleOnPageChangeListener` -- 0-based by Android convention
-- not from any doc or test); and whether `onPageChanged` fires sensibly in
scroll mode, where "page" has no real meaning (the existing empty-label guard
should just hide the indicator there, but this has not been confirmed on a
device).

### BUG-19 — Pocket TTS occasionally duplicated a word that is not in the text

**Reported** 2026-08-19, on `the-tell-tale-heart.epub`, `pocket_tts` engine,
`af_heart_clone` voice. **Fixed** 2026-08-19, after a second report. **Severity**
low -- an occasional artifact, not a correctness bug, but audible.

**Symptom** "some words were duplicated in the audio but not the text." Seen
again later on `OceanofPDF.com_Supermarket_-_Bobby_Hall.epub`, page 20: "the
words 'then burgers' repeated" -- traced to the book's own text, "...then went
on to fries, then burgers, then cashier, then shift manager..." (a long,
comma-heavy list sentence).

**Cause.** Not an app bug -- checked `core.py`'s per-sentence synthesis loop
for a pipeline cause (a retry that doesn't discard a partial attempt, chunk
overlap) and it is clean, one `engine.synthesize()` call per sentence. Pocket
TTS itself silently re-splits any sentence over 50 tokens
(`MAX_TOKEN_PER_CHUNK`) into independently-generated chunks with **no
continuity between them** -- its own source has a TODO acknowledging this ("a
very simplistic way of handling long texts... we could do much better using
teacher forcing"). Both reported cases were long: Poe's sentences in the first
book measured 71-84 tokens, the Supermarket list sentence in the second was
comparably long -- both well over the 50-token threshold, so both hit this
seam. A repeated word right at a chunk boundary is exactly what a missing
teacher-forcing continuity would produce. (A second contributing factor also
confirmed, not fixed: Pocket TTS samples with `temp=0.7` and no noise clamp by
default, genuine stochastic variance that is part of what gives it more
expressive delivery than Kokoro, at the cost of occasional instability
regardless of sentence length.)

**First decision, after the first report:** generated the same real sentences
at default settings and at a more conservative one (`temp=0.5`,
`noise_clamp=2.5`) for an A/B listen, and chose to **keep the defaults** --
not worth the expressiveness trade for an occasional artifact. Revisited after
the second report landed on a real, different sentence.

**Fix**, in `engines.py`'s `PocketTTSEngine`: rather than trading
expressiveness away for the *whole* book, `synthesize()` now tokenizes each
sentence with Pocket TTS's own tokenizer before generating it and only
switches to the more conservative settings (`temp=0.5`, `noise_clamp=2.5`)
when it is actually longer than `MAX_TOKEN_PER_CHUNK` -- the one case
confirmed at real risk of the chunk-boundary seam. Short sentences (the vast
majority of dialogue) keep the library's own default temperature and full
expressiveness. `model.temp`/`.noise_clamp` are read fresh on every generation
step (`tts_model.py`'s `_run_flow_lm`), so mutating them on the one shared
model instance per call works without a second model loaded -- confirmed
directly (`e.model.temp` before/after a short vs. a 69-token sentence) and
through a real end-to-end conversion.

Unverified: whether this fully eliminates the artifact, or only reduces its
odds -- `temp=0.5`/`noise_clamp=2.5` was chosen from one earlier A/B listen,
not tuned specifically against this failure mode, and Pocket TTS's chunk
splitter operates on its own tokenization of the *prepared* prompt, not the
raw sentence text checked here, so the exact token count this compares
against is an approximation of the library's real internal threshold, not a
byte-for-byte match.

### BUG-18 — A tap on blank page space could enter fullscreen, not just leave it

**Reported** 2026-08-19. **Fixed** 2026-08-19. **Severity** low, but a surprising
gesture the user did not ask for.

**Symptom** "we should not allow going to fullscreen mode by clicking an area in
the page. fullscreen should only happen via the fullscreen button on the
toolbar."

**Cause.** `ReaderScreen.kt`'s tap handler treated a tap landing on nothing (no
word resolved, no menu open) as `onToggleImmersive()` -- a plain toggle, so it
both entered and left fullscreen depending on the current state. Only the
toolbar's own button was meant to be a way *in*; the toggle made blank space do
the same thing.

**Fix.** The callback into `EpubNavigator` is one-way now (`onExitImmersive`,
`{ immersive = false }`), never a toggle. A page tap can still *close*
fullscreen -- there is no other way back, since the toolbar carrying the
button is itself hidden while immersive -- but can no longer open it. Only
`IconButton(onClick = { immersive = true })` on the toolbar does that.

### BUG-17 — Word tap unreliable, and "Read from here" sometimes jumped pages back

**Reported** 2026-08-19. **Fixed** 2026-08-19. **Severity** high -- this is the
read-along feature's core interaction.

**Symptom** "some times i click on a word to get the context menu and it
doesnt work. other times i click on a word and select read from here and it
jumps to a spot several pages back."

**Cause.** `ChunkIndex.indexOfTap` matches the word a tap resolved to against
the sync mapping by walking the block's sentences and text together with a
cursor, requiring each sentence's stored `textHighlight` to appear as a
*literal, unnormalized* substring of the tapped block's text. That text comes
from two different parsers on the same epub markup: `textHighlight` was
extracted at conversion time with jsoup (`EpubTextExtractor`), the tapped
block's text comes from the WebView's rendered DOM at tap time (Blink). The
two usually agree byte-for-byte but not always -- a differently decoded
entity, a stray smart quote -- and when they didn't, the literal match failed
silently and fell through to a fallback that searched the *entire chapter* for
matching content and returned the first occurrence. For any phrase that
repeats ("he nodded," a dialogue tag, anything common), that first occurrence
can be pages before the tap -- exactly the reported symptom. This is also
almost certainly what caused some taps to seem to "not work": a tap that
should have opened the menu on the correct word could resolve to a match many
paragraphs away, distant enough from the tap's own screen position that
nothing visibly happened where the finger was.

**Fix**, in `ChunkIndex.kt`:
1. The primary cursor-walk now compares `textHighlight` against the tapped
   block's text in the same normalized space (case, whitespace, quotes, dashes
   folded) `TextNormalizer` already uses for the aligner, rather than requiring
   a literal match. This alone should resolve the large majority of cases,
   since it is exactly the kind of superficial difference two parsers produce.
2. When even that fails, the fallback -- reworked to share its three-strategy
   content match with `indexOfSelection` via a new private `matchByContent`,
   rather than being a weaker, one-strategy reimplementation of it -- now tries
   candidates at-or-after whichever sentence the walk last successfully placed
   *before* falling back to an unrestricted, whole-chapter search. A repeated
   phrase now resolves to its nearest occurrence, not its first.

Covered by two new/renamed unit tests in `ChunkIndexTest.kt` (30/30 passing).
Unverified on a device beyond the reported case: whether every real-world
jsoup/Blink discrepancy is one `TextNormalizer` already folds, versus one that
still needs the position-scoped fallback.

**Follow-up, same day: still jumped back, on a real book.** Reported again on
the device: "saw the bug again where i select read from here and it jumps
back. page 15 of the supermarket book. it jumps back to page 11." This was a
deeper, different bug than the one above -- not a match *failing* at all.

**Cause, confirmed against the real generated sync file** (9,724 chunks, 32
chapters): `indexOfTap`'s cursor walk iterates every aligned chunk in the
*whole chapter*, in chapter order, and assigns a match to whichever candidate
it reaches first whose text is found anywhere in the tapped block. That is
correct when a sentence's wording is unique within the chapter, but this
book's dialogue is not: `"What do you mean?"` appears 3 times in one chapter,
`"she interrupted."` twice 234 chunks apart, `"Think about it, man."` twice
only 11 chunks apart -- 59 distinct duplicated lines in this book alone,
several within a few hundred chunks of each other (roughly a handful of pages,
by the book's own 9,724 chunks / ~400 minutes). Tapping the *second*
occurrence of such a line, on its own paragraph, with a perfectly good
normalized match available, still resolved to the *first* occurrence in the
chapter -- because the walk had no notion of *where the reader currently is*,
so an identical line pages earlier was exactly as valid a candidate as the
reader's own paragraph, and chapter order always favoured the earlier one.

**Fix**, in `ChunkIndex.kt`: `indexOfTap` takes an optional `anchorIndex` --
the reader's approximate current position, threaded through from
`ReadAlongViewModel.onWordTapped` as `ReadAlongState.currentIndex` (the
currently playing or last-known sentence). The cursor walk now runs first
against only candidates within `ANCHOR_WINDOW` (40 chunks) of that anchor,
which excludes a duplicate several pages away from contention entirely before
chapter-order bias can pick it; only if nothing matches nearby does it retry
unrestricted, so a reader paused and paged well ahead of or behind the audio
(or with no playback position yet) still gets a tap resolved, just without the
extra precision. `ANCHOR_WINDOW` is a reasoned guess, not a measurement --
pagination depends on font size and screen, which `ChunkIndex` has no way to
know, so there is no way to derive a true chunks-per-page figure here.

Covered by two new unit tests reproducing the exact mechanism (a distant
duplicate winning with no anchor, an anchor correcting it, a stale/missing
anchor still falling back correctly) -- 32/32 passing. Unverified beyond the
reported case: whether 40 chunks is the right window on other books, and
whether `currentIndex` is usually close enough to the true tap position in
practice (it will be stale if the reader has paged far ahead of playback with
"follow" off).

**Follow-up, next day: the anchor fix made it worse, not better.** Reported:
"with the latest changes the read from here feature is completely broken. the
pages jump around." The "unverified" concern above was the actual bug, and it
was worse than a stale anchor -- it was almost always the *wrong kind* of
anchor.

**Cause.** `currentIndex` is the currently-*playing* (or last-*played*)
sentence. "Read from here" exists specifically to move listening to somewhere
*other* than where it currently is -- paused and browsing ahead, resuming
after a break, starting the book before ever pressing play -- so audio
position and tap position agreeing was the exception, not the rule. Any tap
under this feature's own primary use case anchored the windowed search to a
position with no real relationship to the tap, and unlike a merely *stale*
anchor (which finds nothing nearby and correctly falls back to the
unrestricted search), a *wrong* anchor within `ANCHOR_WINDOW` of *something*
routinely found a coincidental match and returned it with full confidence --
consistent, and consistently wrong, which is what "pages jump around" was:
not a small offset like the original bug, but a resolution that tracked
wherever playback last happened to be, unrelated to where the reader tapped.

**Fix.** Replaced the anchor's source entirely. `indexOfTap` now takes
`readingProgression`, a 0.0-1.0 fraction *within the current resource* --
Readium's own `Locator.Locations.progression` from `fragment.currentLocator`
at the moment of the tap, threaded through `ReaderScreen.kt` ->
`ReadAlongViewModel.onWordTapped` -> `ChunkIndex.indexOfTap`, which converts
it into an approximate position among that resource's own candidates
internally (no absolute chunk index needs to leave `ChunkIndex` for this).
This reflects where the reader is *looking*, not where audio last was --
by construction always near the tap, regardless of playback state, which is
the property the fix actually needed. `ANCHOR_WINDOW` (40 chunks) and the
windowed-then-unrestricted structure are otherwise unchanged.

The two tests from the previous fix were updated to the new parameter (a
progression fraction, not a raw index) rather than replaced -- same
mechanism, same regression coverage, now exercising the corrected anchor
source. 32/32 passing.

Unverified: whether `Locations.progression` is reliably populated at tap
time on a real device for every book (a locator missing it degrades to no
reading-position hint, i.e. the pre-anchor behaviour, not a crash -- but this
has not been confirmed against real Readium output beyond the unit tests).

**Follow-up, still broken: "was on page 16 of chapter 3 and it jumped to page
2 of chapter 3."** Same chapter this time, ruling out a resource/href mixup --
narrowed to *where within the chapter* the anchor itself was pointing.

**Cause, inferred from decompiling `EpubNavigatorFragment` (not confirmed on
a device -- no way to attach a debugger or logger to a real reading session
from here).** `fragment.currentLocator` is updated by
`WebViewListener.onProgressionChanged()` -> `notifyCurrentLocation()`, a
*different* path than the one that reports page turns
(`WebViewListener.onPageChanged(Int, Int, String)`, bridged up to the
`PaginationListener` this app already listens to for BUG-20). If
`currentLocator` updates on a different cadence than the page turns
themselves -- plausible for a progression-change notification, not confirmed
-- then a tap read from `fragment.currentLocator.value` right after several
quick page turns could still reflect an earlier page, closer to wherever the
chapter was first entered than to where the reader actually is. "Jumped to
page 2" -- close to the start of chapter 3 -- fits a stale locator still
anchored near the chapter's beginning.

**Fix.** The `PaginationListener` registered for BUG-20 already receives a
`Locator` argument on every page change -- Readium's own, guaranteed to be
for the page just turned to. `ReaderScreen.kt` now stores it
(`lastPageLocator`) and the tap handler reads from that first, falling back
to `fragment.currentLocator.value` only if no page change has been observed
yet, for both the tap's `resourceHref` and `readingProgression` -- removing
the question of whether `currentLocator` itself is caught up at all.

**This fix is not verified against a real device or a real Readium session.**
The mechanism (a second, possibly-lagging update path for `currentLocator`)
is inferred from bytecode structure -- real method names and call graph, not
guessed -- but its actual timing under real page-turn gestures has not been
observed directly, only reasoned about.

**Follow-up, still broken, no further detail given ("still broken").** Three
rounds of fixes to `ChunkIndex.indexOfTap` in a row had not moved the needle
at all -- worth treating as a signal in itself that the resolved sentence
index was probably fine all along, and the bug was somewhere *after* it.
Checked that assumption directly this time instead of proposing a fourth
theory about matching.

**Cause, actually confirmed by reading the code paths involved, not
inferred.** `ReadAlongViewModel.playFrom(chunkIndex)` -- what "Read from
here" calls -- seeks the player and sets `_state.value.currentIndex =
chunkIndex` **immediately**, but never touches `_navigateTo`, the signal
`ReaderScreen.kt`'s `LaunchedEffect(navigator, navigateTo)` actually acts on
to move the page (`fragment.go(locator, ...)`). The only other way
`_navigateTo` gets set is the poll loop, gated on
`nextIndex != _state.value.currentIndex` where `nextIndex` comes from real
playback position (`index.indexAt(player.currentPositionMs())`). `ExoPlayer`
completes a seek asynchronously, so the very next poll tick after `playFrom`
can still read the *pre-seek* position -- a `nextIndex` that differs from the
`currentIndex` `playFrom` already overwrote, which reads to the poll loop as
"the sentence changed" and sends the page to the *stale* position instead.
Once the seek genuinely lands and `nextIndex` catches up to the real target,
it now equals `currentIndex` (already set), so the condition is false and the
correct destination is never sent at all. Deterministic, not timing-flaky in
principle -- explains a *consistent* wrong destination, not an occasional one,
matching every report in this bug's history. `resumeFollowing()` elsewhere in
the same file already sets `_navigateTo.value` directly for exactly this
reason ("move to the current sentence... even if it was the last target");
`playFrom` was simply missing the equivalent line.

**Fix.** `playFrom` now sets `_navigateTo.value = chunkIndex` directly,
matching `resumeFollowing()`'s pattern, instead of relying on the racy poll-
loop detection. Also dropped `playFrom`'s own direct `follower.onNavigated()`
call, now premature and redundant -- `ReaderScreen.kt`'s effect already calls
`onNavigationHandled()` (which calls it properly) once the real navigation
completes.

Not covered by a unit test: `ReadAlongViewModel` has no test suite of its own
-- `FollowController` was deliberately pulled out as a plain state machine
specifically because this class needs a device to exercise
(`FollowController.kt`'s own docstring). Needs real-device confirmation.

**Follow-up, still broken -- and this time actually confirmed, not
theorized.** Four fixes in a row (normalization, an anchor from playback
position, an anchor from reading position, a fresher page locator) had not
helped. Rather than propose a fifth theory, added a temporary diagnostic
snackbar (same approach as BUG-9's history) showing exactly which chunk got
selected and navigated to, and asked for a real repro. Screenshots came back
showing `idx=488 prog=0.045 href=ch03.html hl=","` -- the resolved chunk's
own stored text was a **single comma**, not the tapped sentence, sitting near
the very start of chapter 3. That matches "jumped to page 2" exactly, and
explains why nothing about anchoring or locator freshness ever helped: the
bug was never about *where* the search looked, it was about a degenerate
match winning regardless of where it looked.

**Cause, confirmed.** `ChunkIndex.matchByContent`'s second strategy ("the
selection spans sentences; start at the first one inside it") checks
`needle.contains(chunk.text)` with no minimum length on `chunk.text`. A
chunk whose own text is a lone `","` -- an audiblez sentence-splitting edge
case, evidently a real one in this book -- trivially satisfies that check for
almost *any* real paragraph, since nearly every sentence contains a comma
somewhere. Once the primary (position-aware) match failed for any reason at
all, this fallback would find such a fragment and return it immediately,
completely independent of position -- explaining why every round of "fix the
anchor" was aimed at the wrong mechanism. `MIN_PARTIAL_MATCH` (8 characters)
already encoded "below this many characters a match is more likely a
coincidence than intent" for the third (shrinking-prefix) strategy; the same
reasoning had just never been applied to the first two.

**Fix.** Both of `matchByContent`'s first two strategies now require at least
`MIN_PARTIAL_MATCH` characters on whichever side of `contains()` could
otherwise be degenerate (the needle for strategy 1, the chunk's own text for
strategy 2) before considering a match at all.

**Verified, not just reasoned about this time**: reverted the fix locally,
confirmed the new regression test
(`a degenerate one-character chunk never wins a content match by
coincidence`, built directly from the real `","` chunk reported) fails
without it, then restored the fix and confirmed all 33 tests pass.

**Confirmed fixed on device.** Retested after the caching issue that had
been serving a stale APK (unrelated -- browser/Downloads reused an old file
by name; fixed by deleting it before re-downloading) was sorted out. The
temporary diagnostic snackbars (`ReadAlongViewModel.debugInfo`, the
`onMessage("go: ...")` call in `ReaderScreen.kt`) have been removed now that
this is confirmed, same as BUG-9's precedent.

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
