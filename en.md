# Enhancements

Things the app should *do*, as opposed to things it does wrong. Defects go in
[`BUGS.md`](BUGS.md) — the rough test is whether it is already meant to work: a page
that will not turn is a bug, a page counter that does not exist yet is an enhancement.
When in doubt put it here; moving it costs nothing.

**Add yours to [Requested](#requested).** Copy the template, fill in what you can.

## How to file one

```markdown
### One line saying what you want
- **Where**: library / import / reader / player / settings / server
- **Why**: what you are actually trying to do
- **Must / nice**: is this blocking your reading, or would it just be better?
- **Notes**: anything specific — how it should look, when it should happen
```

Only the title is essential. **Why** is the most useful of the rest: knowing the
underlying goal often turns up a simpler or better answer than the feature as
literally described — asking for a page counter is really asking "how far through am
I?", and a percentage might have answered it.

---

## Requested

*Nothing waiting. Add new enhancements here.*

---

---

## In progress

### E-1 — Make conversion fast enough for a real novel

- **Where**: server / audiblez
- **Why**: at ~5× realtime on CPU a 10-hour audiobook takes about 2 hours, which
  makes converting an actual book something you plan rather than something you do.
- **Must / nice**: must, for the project to be usable beyond samples.

Tracked as a limitation in [`BUGS.md` BUG-4](BUGS.md) with the measurements. The work
itself is an enhancement, in rough order of expected payoff per risk:

1. **Sentence batching.** Every sentence is currently its own forward pass. Batching
   helps on any backend and needs no new hardware support — the cheapest real win.
2. **ONNX Runtime.** Kokoro ships ONNX exports, and ONNX Runtime sidesteps the
   torch-on-ROCm problem entirely. Likely the most reliable route on this hardware.
3. **torch + ROCm.** The obvious-looking option and the one to try last: gfx1150
   usually needs `HSA_OVERRIDE_GFX_VERSION=11.0.0`, and an unsupported iGPU under
   ROCm can be *slower* than CPU or simply fail to load.

Whatever the backend, the sync metadata has to keep coming from frame counts
(`len(audio) / 24000`) rather than a wall clock, or the timings stop matching the
audio and read-along breaks.

---

## Done

Kept rather than deleted: several of these record a decision worth remembering.

### E-7 — Tap a word for a menu: Read from here, and Definition

*2026-08-13.* Implemented as requested. Original request kept below.

- **Tap a word** → the word is highlighted and a two-item menu opens beside it.
  Nothing else changes: no playback, no scrolling, no other visual state.
- **Read from here** → plays from the start of the sentence that word is in. Hidden
  when the passage has no audio mapped to it, rather than offered and then failing.
- **Definition** → pauses playback and shows a sheet with the definition.
- The **"Not following the audio"** note is gone for good, along with the alignment
  note beside it — both appeared and disappeared, changing the bar's height and
  shifting the page on every tap. Follow state is the crosshair's tint now; alignment
  quality lives on the book's detail screen.

**The dictionary is WordNet 3.0, bundled in the APK** (10.5 MB of data, ~5 MB
compressed) so it works with no network at all. Its licence permits redistribution
provided the copyright notice ships with it, which it does, in
`assets/dictionary-LICENSE.txt` and credited in the definition sheet.
`tools/build_dictionary.py` rebuilds the database from the WordNet release, so the
asset is reproducible rather than a mystery blob.

Two details worth keeping:

- **Storage is normalised** — words point at shared synsets. A definition belongs to a
  synset shared by several words, so storing it per word roughly trebled the file.
  Multi-word entries were dropped too: tapping one word can never match "united
  states of america".
- **The literal word wins over its stem.** "computing" and "better" are entries in
  their own right, so a reader tapping them gets *those* definitions, not "compute"
  and "good". Inflections fall back to WordNet's exception lists ("went" → "go",
  "mice" → "mouse") and then to suffix rules.

<details>
<summary>Original request</summary>

### Clicking on a single word to generate a menu for more actions
- **Where**: frontend
- **Why**: i want to add move actions: 1) Read from here, 2) Definition
- **Must / nice**: must
- **Notes**: 

Whenever I click on a word, highlight the word and show a context menu with the two options 1) Read from here, 2) Definition. 
Remove the note at the bottom of the app that says "Not following the audio...". Don't ever show this note again. This note makes the app distracting since everytime i click the screen jumps up to display this note
When i click on a single word the only thing that should happen is the context menu should display right next to the word i clicked on. keep it simple and don't make any other visual changes on this click.
Implement the read from here option after i select that option. essentially it should read from the beginning of the sentence that word is in. 
if i select definition, the play should stop and a small screen should appear with the definition of the word. install a dictionary along with the app since i want this to work offline. i don't want to have internet connection for this to work

</details>

### E-2 — Default the server address so a reinstall does not need Settings

*2026-08-12.* Defaults to the Tailscale address of the development machine, which —
unlike a LAN address — survives DHCP and works away from the house. A *default*, not a
hard-coding: Settings still overrides it, and clearing the field falls back to it
rather than to nothing.

### E-3 — An e-ink colour scheme

*2026-08-12.* "Paper" in the appearance sheet: a warm grey page (`#D8D4C8`) with
near-black ink (`#1B1B18`), given to Readium as explicit background and text colours.

Two things worth remembering. Publisher styles are switched **off** for this theme
only, because they are the one thing that can override page colours — an epub setting
its own `body { background: #fff }` would punch a white hole through the grey. And the
whole reader screen takes the palette, not just the page: a grey page inside a white
window reads as a rendering fault rather than a choice.

### E-4 — Let the text fill more of the screen

*2026-08-12.* Three separate causes, since Readium's own vertical padding is already
zero:

- `RsProperties(pageGutter = 8px)` shrinks the horizontal padding at its source —
  `pageMargins` is only a multiplier on it, so lowering the base is what lets text
  reach the edges. A **Margins** slider tunes it, defaulting to 0.5.
- `flowSpacing = 0.5rem` tightens the vertical rhythm between blocks, which is most
  of the gap above a chapter heading.
- A **full screen** button hides both the app's toolbars *and* the system bars, and
  drops the Scaffold's inset reservation, so the page reaches the physical edge.

What could not be removed: a heading's own top margin comes from the publication's
CSS, which is preserved deliberately — that is the point of rendering through Readium.
The exception is the Paper theme, where publisher styles are already off.

### E-5 — Show where you are in the book

*2026-08-12.* A muted `24 / 312` at the bottom centre of the page, over the text
rather than in a bar, so it survives full-screen mode.

The numbers are Readium **positions**, not pages: a reflowable epub has no real pages
since the count depends on font size and screen. Positions are stable slices that do
not change when the text is resized, which is the honest equivalent — and why the
total will not match a print edition. Falls back to a percentage if a publication
cannot produce positions.

### E-6 — Start the audio from a chosen sentence

*2026-08-12.* Two ways in, deliberately:

- **Single tap** on a sentence offers *"Read from here"* with the matched sentence
  shown; a second tap on **Play** commits. Two steps because a tap is also how pages
  are turned — jumping the audio on every stray touch would be worse than one extra
  tap. Showing the matched sentence doubles as a check that the tap resolved correctly.
- **Long-press → Read from here** in the selection toolbar plays immediately, since
  that is already an explicit choice from a menu.

Neither uses the invisible tappable-decoration layer, which depends on Readium
rendering a transparent decoration that can still receive a touch — the mechanism that
caused BUG-3.

---

## Ideas

Not requested; noted while working. Here so they are not lost, not because they are
planned.

- **On-device tests.** All 162 tests are JVM/Robolectric. The bugs found on hardware
  so far — controls under the navigation bar, every line highlighted — are exactly the
  class a unit test cannot see. Worth doing once the current fixes are confirmed, so
  the tests encode known-good behaviour rather than a guess.
- **Word-level highlighting.** The sync file is sentence-level by construction
  (`audiblez/SYNC.md`). Going finer would mean changes on the audiblez side to emit
  per-word timings, and would make the mapping several times larger.
- **A library-wide "convert everything" queue**, so a shelf of epubs can be left
  running overnight — considerably more attractive once E-1 lands.
