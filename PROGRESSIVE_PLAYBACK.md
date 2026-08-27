# Progressive (chapter-by-chapter) playback

Not started. Scoped 2026-08-27 for later, once the current app has had real
users and real feedback -- written down now so the scoping work isn't lost.

## Context

Right now a user has to wait for an entire book to finish converting --
which can be many minutes for a long book -- before they can read or listen
to any of it, even though audiblez already synthesizes one chapter at a
time. The goal: let someone start listening to chapter 1 as soon as it's
ready, while the rest of the book keeps converting (and then downloading)
in the background, instead of gating the whole experience on the final
`status: done`.

This is **not** true live-streaming of one growing audio file. It's
per-chapter availability -- closer to how a podcast app queues episodes
than to a live radio stream. That distinction is what keeps this feasible:
audiblez already produces one `.wav` per chapter before merging everything
into the final `.m4b`, so the chapter boundary is a natural, already-existing
seam to build on, and nothing about audio container streaming (partial
`.m4b` boxes, chunked transfer, etc.) needs solving.

## Why this isn't a quick tweak

**Server** (`audiblez/audiblez/core.py`, `server/app/tasks.py`): chapters
synthesize sequentially or across parallel workers
(`_run_chapters_parallel`), and can **finish out of reading order** --
that's the whole point of parallelizing independent chapters. Only once
every chapter is done does `core.main()` merge them into one `.m4b` and
write one sync `.json`; the job manifest only gets `audiobook`/`sync` file
references at that point. There is currently no concept of "chapter 3 of 12
is ready" anywhere in the manifest or API.

**App**: playback is built around exactly one `ExoPlayer` item and read-along
around exactly one, complete sync timeline (`SyncDao`). `ResumableDownloader`
fetches the single finished `.m4b` in one shot after polling reports `DONE`.
None of this currently has a notion of "partially ready."

## Recommended approach

1. **Server: expose per-chapter status as a first-class thing, not an
   internal implementation detail deleted after the merge.**
   - Keep parallel chapter synthesis (don't sacrifice its speed win), but
     track a **highest contiguous ready chapter** cursor server-side --
     chapter *N* only counts as "ready to play" once every chapter before
     it in reading order is also done, even if a later chapter's worker
     happened to finish first.
   - New endpoints: something like `GET /api/jobs/{id}/chapters` (index,
     duration, ready/not-ready) and `GET /api/jobs/{id}/chapters/{n}/audio`
     + `.../sync`, alongside the existing whole-job endpoints, which keep
     working exactly as they do today once the job reaches `DONE`.
   - Changes the current disk-reclaim assumption: `keep_intermediate`
     currently deletes per-chapter `.wav`s once the `.m4b` exists (see
     server/README.md); progressive playback needs those to survive long
     enough for every interested device to have downloaded them.

2. **App: a playback queue instead of one file, and incremental sync data.**
   - Media3 supports appending items to a live playlist -- start playback at
     chapter 1 and append each new chapter's audio as it lands, rather than
     handing ExoPlayer one finished file.
   - Sync chunks get merged into `SyncDao` per chapter as they arrive
     (already chapter-indexed, per the existing sync format) instead of
     assuming one complete timeline up front.
   - The existing `PollWorker`/`DownloadWorker` background pattern
     generalizes naturally: poll per-chapter completion, download each
     newly-ready chapter in the background while playback continues -- this
     is the part that already matches "keep converting and downloading in
     the background" from the original ask.
   - New UI state between "converting" and "ready": something like
     "Partially ready (3/12 chapters) -- tap to start listening," distinct
     from the existing `BookStage.CONVERTING`/`AVAILABLE`/`READY`.

## Open questions to settle before building

- **Seeking past the ready boundary.** What happens if the user jumps ahead
  into a chapter that hasn't synthesized yet? Needs explicit UX -- disable
  seeking past the boundary, or show a spinner and auto-play once it lands.
- **Opt-in or default?** Recommend making it the automatic behavior for
  every conversion once built (matches the "get people listening sooner"
  motivation), but worth deciding explicitly rather than defaulting silently.
- **Interaction with cancel** (`LibraryViewModel.cancelConversion`, added
  this session): if a book is cancelled after several chapters are already
  playable, do those downloaded chapters stay playable, or does everything
  get cleared? Recommend keeping whatever's already downloaded, matching the
  existing "cancel leaves the card retryable" behavior for the whole-book case.
- **Supertonic's different sample rate** (44100Hz vs Kokoro/Pocket TTS's
  24000Hz) already needed special handling for the single-file sync timeline
  (see server/README.md, "Chapters synthesize in parallel"). Per-chapter
  playback needs the same rate-awareness per chapter -- likely a non-issue
  since it's already resolved per-job today, just worth re-checking once
  this is chapter-granular.

## Suggested sequencing, whenever this gets picked up

1. Server: chapter-level status/endpoints + the contiguous-ready cursor.
   Testable with curl and the existing test patterns alone, no app changes.
2. App: playback queue + progressive sync loading for one test book, behind
   whatever's convenient for manual testing.
3. App: the "partially ready" card/reader UX.
4. Wire background chapter-download into the existing Poll/DownloadWorker.
5. Decide on and implement the disk/cleanup semantics change.
