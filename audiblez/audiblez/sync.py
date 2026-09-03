# -*- coding: utf-8 -*-
"""Text-to-audio synchronisation metadata for read-along playback.

audiblez already knows exactly how much audio every text chunk produced -- it
just throws that information away after concatenating the samples.  This module
keeps a running tally of it so the reader app can highlight the sentence that is
currently being spoken.

Timestamps are seconds measured from the start of the whole audiobook (the
timeline a player reports for the final .m4b), not from the start of the
chapter, because that is what the player hands us back at playback time.

Durations come from frame counts rather than from a clock:

    chunk_duration = len(audio_array) / sample_rate

which is exact, whereas wall-clock timing of the generation loop is not.
"""

import json
from pathlib import Path

SYNC_FORMAT_VERSION = 1


def num_frames(audio):
    """Number of audio frames in a chunk produced by a TTSEngine.

    Accepts either a numpy array or a torch tensor, mono or with a leading
    channel dimension.
    """
    shape = getattr(audio, 'shape', None)
    if shape is None:
        return len(audio)
    if len(shape) == 0:
        return 0
    return int(shape[-1])


class SyncTimeline:
    """Running tally mapping text chunks onto the audiobook's timeline.

    Usage mirrors the shape of the generation loop::

        timeline = SyncTimeline(sample_rate)
        for chapter in chapters:
            timeline.begin_chapter(index, title, source)
            for sentence in sentences:
                timeline.add_chunk(sentence, frames)
            timeline.end_chapter()
    """

    def __init__(self, sample_rate):
        self.sample_rate = sample_rate
        self.current_time = 0.0
        self.chunks = []
        self.chapters = []
        self._open_chapter = None

    # -- chapter bookkeeping -------------------------------------------------

    def begin_chapter(self, index, title=None, source=None):
        if self._open_chapter is not None:
            raise RuntimeError(f'chapter {self._open_chapter["index"]} was never ended')
        self._open_chapter = {
            'index': index,
            'title': title,
            'source': source,
            'start': self.current_time,
            'end': self.current_time,
            'first_chunk': len(self.chunks),
        }

    def end_chapter(self):
        """Close the open chapter and return its record plus chapter-relative chunks.

        The relative chunks are what gets cached next to the chapter's .wav so a
        resumed run does not have to re-synthesise it just to learn its timings.
        """
        if self._open_chapter is None:
            raise RuntimeError('end_chapter() called with no chapter open')
        chapter = self._open_chapter
        self._open_chapter = None
        chapter['end'] = self.current_time
        offset = chapter['start']
        relative = [
            {'text': c['text'], 'start': c['start'] - offset, 'end': c['end'] - offset}
            for c in self.chunks[chapter.pop('first_chunk'):]
        ]
        self.chapters.append(chapter)
        return chapter, relative

    # -- chunks --------------------------------------------------------------

    def add_chunk(self, text, frames):
        """Append one synthesised chunk and advance the tally."""
        start = self.current_time
        end = start + frames / self.sample_rate
        chunk = {
            'text': text,
            'start': start,
            'end': end,
            'chapter': self._open_chapter['index'] if self._open_chapter else None,
        }
        self.chunks.append(chunk)
        self.current_time = end
        return chunk

    def add_cached_chapter(self, index, duration, relative_chunks, title=None, source=None):
        """Splice in a chapter whose audio already exists on disk.

        `duration` is authoritative for how far the timeline advances -- it is
        measured from the .wav itself -- so a stale or truncated cache can never
        desynchronise everything that follows it.
        """
        self.begin_chapter(index, title, source)
        start = self.current_time
        for chunk in relative_chunks:
            self.chunks.append({
                'text': chunk['text'],
                'start': start + chunk['start'],
                'end': start + chunk['end'],
                'chapter': index,
            })
        self.current_time = start + duration
        return self.end_chapter()

    # -- output --------------------------------------------------------------

    def to_dict(self, title=None, author=None, audio_file=None):
        return {
            'version': SYNC_FORMAT_VERSION,
            'title': title,
            'author': author,
            'audio_file': audio_file,
            'sample_rate': self.sample_rate,
            'duration': self.current_time,
            'chapters': self.chapters,
            'chunks': self.chunks,
        }


def _round_floats(obj, places=3):
    """Millisecond precision is plenty and keeps the file readable."""
    if isinstance(obj, float):
        return round(obj, places)
    if isinstance(obj, dict):
        return {k: _round_floats(v, places) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_round_floats(v, places) for v in obj]
    return obj


def write_sync_file(path, timeline, title=None, author=None, audio_file=None):
    """Write the sync mapping next to the audiobook."""
    payload = _round_floats(timeline.to_dict(title, author, audio_file))
    path = Path(path)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(payload, f, ensure_ascii=False, indent=1)
    return path


# -- per-chapter cache, so resumed runs keep their timings --------------------

def chapter_sync_path(chapter_wav_path):
    return Path(str(chapter_wav_path) + '.sync.json')


def save_chapter_sync(chapter_wav_path, duration, relative_chunks):
    """Cache a chapter's timings next to its .wav.

    Kept at full float precision, unlike the final output: these values get
    re-offset onto the timeline on a resumed run, and rounding them first makes
    a resumed run disagree with an uninterrupted one by a millisecond or two.
    """
    path = chapter_sync_path(chapter_wav_path)
    payload = {'duration': duration, 'chunks': relative_chunks}
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(payload, f, ensure_ascii=False, indent=1)
    return path


def load_chapter_sync(chapter_wav_path):
    """Return the cached chunk list for a chapter, or None if unusable."""
    path = chapter_sync_path(chapter_wav_path)
    if not path.exists():
        return None
    try:
        with open(path, encoding='utf-8') as f:
            payload = json.load(f)
        return payload['chunks']
    except (ValueError, KeyError, OSError):
        print(f'Warning: ignoring unreadable sync cache {path}')
        return None
