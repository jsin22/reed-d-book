# Sync metadata fork

Fork of [audiblez](https://github.com/santinic/audiblez) at upstream commit
`788a6a81`, modified to emit a text-to-timestamp mapping alongside the `.m4b`
so a reader app can highlight text as the audio plays.

## What it produces

For `book.epub` you now get `book.m4b` **and** `book.json` in the output folder:

```json
{
  "version": 1,
  "title": "The Test Book",
  "author": "A. Writer",
  "audio_file": "book.m4b",
  "sample_rate": 24000,
  "duration": 15.275,
  "chapters": [
    {"index": 1, "title": "Chapter 1", "source": "chap_01.xhtml", "start": 0.0, "end": 6.575}
  ],
  "chunks": [
    {"text": "This is the first sentence of chapter 1.", "start": 3.675, "end": 6.575, "chapter": 1}
  ]
}
```

`chunks` is the mapping proper: one entry per sentence, in playback order,
contiguous (`chunks[n].end == chunks[n+1].start`) with no gaps or overlaps.
`chapters` is there so the app can flip pages on chapter boundaries without
re-deriving them.

**Times are seconds from the start of the whole audiobook**, not from the start
of the chapter — that is what a player reports back during playback.

Durations come from frame counts, never from a wall clock:

    chunk_duration = len(audio_array) / 24000

Sentences that Kokoro internally splits into several segments are recorded as a
single chunk, since they are contiguous in the output.

## Known caveats

- **~40–90 ms of AAC padding.** The timestamps describe the concatenated WAV
  timeline exactly. The `.m4b` is then AAC-encoded, which adds priming samples:
  a test book measured 15.317 s as `.m4b` against 15.275 s of real audio. If
  highlighting runs consistently early by a fixed few tens of milliseconds,
  subtract a constant offset in the player rather than changing this file.
  ExoPlayer honours the gapless metadata ffmpeg writes, so it may be a non-issue
  — worth measuring on device before compensating.
- **Chapter 1 starts with an injected chunk.** audiblez prepends
  `"<title> – <author>."` to the first chapter, and it is real spoken audio, so
  it appears as the first chunk. Its text is not in the epub; the app should
  expect chunk 0 not to match any epub text.
- **Chunk text is verbatim from the sentence splitter**, including leading
  newlines, so it can be string-matched against the epub. It is not normalised.
- Chapter text is extracted from `title/p/h1-h4/li` tags only, and a `.` is
  appended to anything not ending in one — so chunk text can differ from the
  epub by a trailing period.

## Resuming

audiblez skips chapters whose `.wav` already exists. Since that audio is not
re-synthesised, its sentence timings are cached in
`<chapter>.wav.sync.json` (full float precision, so a resumed run reproduces an
uninterrupted run byte for byte).

If that cache is missing — e.g. `.wav` files from an older audiblez — the
chapter falls back to a single coarse chunk covering its whole text, and a
warning is printed. The timeline still advances by the `.wav`'s **real**
duration, so a stale or absent cache can never desynchronise later chapters.
Delete the `.wav` and re-run to get sentence-level sync back.

## Other fixes needed to run on Fedora

- `set_espeak_library()` only globbed `/usr/lib/*/libespeak-ng*` (Debian's
  layout). Now also checks `/usr/lib64`, which is where Fedora puts it.
- `concat_wavs_with_ffmpeg()` hardcoded `libfdk_aac`, which distro ffmpeg builds
  omit for licensing reasons. Falls back to the native `aac` encoder; harmless,
  since `create_m4b` re-encodes to 64k aac regardless.

## Setup

audiblez pins `python >=3.10,<3.13`:

```sh
python3.10 -m venv .venv
.venv/bin/pip install torch --index-url https://download.pytorch.org/whl/cpu
.venv/bin/pip install -e .
.venv/bin/python -m spacy download xx_ent_wiki_sm
```

Torch is currently the **CPU** build. Moving to ROCm for the Radeon 890M is
still open (project_plan Phase 2); RDNA 3.5 usually needs
`HSA_OVERRIDE_GFX_VERSION=11.0.0`.

## Tests

```sh
.venv/bin/python -m unittest test.test_sync test.test_sync_integration
```

`test_sync.py` covers the timeline arithmetic and has no torch/kokoro/spacy
imports. `test_sync_integration.py` runs `main()` end to end against a fake
Kokoro that returns silence proportional to text length, and asserts the JSON
matches the real durations of the `.wav`/`.m4b` files on disk. Neither needs
network access. The pre-existing `test_main.py` still downloads real books and
needs a real voice.
