#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# audiblez - A program to convert e-books into audiobooks using
# a variety of TTS models for high-quality text-to-speech synthesis
# (see audiblez.engines) -- originally Kokoro-82M, per the project's own
# upstream history (https://github.com/santinic/audiblez); this fork's own
# server pipeline (server/app/tasks.py) uses Kyutai's Pocket TTS.
# by Claudio Santini 2025 - https://claudio.uk
import os

import billiard.pool
import torch.cuda
import spacy
import ebooklib
import soundfile
import numpy as np
import time
import shutil
import subprocess
import platform
import re
from io import StringIO
from types import SimpleNamespace
from tabulate import tabulate
from pathlib import Path
from string import Formatter
from bs4 import BeautifulSoup
from ebooklib import epub
from pick import pick

from audiblez import sync
from audiblez.engines import DEFAULT_ENGINE, engine_sample_rate, load_engine
from audiblez.quote_split import split_into_spans

sample_rate = 24000


def load_spacy():
    if not spacy.util.is_package("xx_ent_wiki_sm"):
        print("Downloading Spacy model xx_ent_wiki_sm...")
        spacy.cli.download("xx_ent_wiki_sm")


def main(file_path, voice, pick_manually, speed, output_folder='.',
         max_chapters=None, max_sentences=None, selected_chapters=None, post_event=None,
         workers=None, engine=DEFAULT_ENGINE):
    if post_event: post_event('CORE_STARTED')
    load_spacy()
    if output_folder != '.':
        Path(output_folder).mkdir(parents=True, exist_ok=True)

    filename = Path(file_path).name

    extension = '.epub'
    book = epub.read_epub(file_path)
    meta_title = book.get_metadata('DC', 'title')
    title = meta_title[0][0] if meta_title else ''
    meta_creator = book.get_metadata('DC', 'creator')
    creator = meta_creator[0][0] if meta_creator else ''

    cover_maybe = find_cover(book)
    cover_image = cover_maybe.get_content() if cover_maybe else b""
    if cover_maybe:
        print(f'Found cover image {cover_maybe.file_name} in {cover_maybe.media_type} format')

    document_chapters = find_document_chapters_and_extract_texts(book)

    if not selected_chapters:
        if pick_manually is True:
            selected_chapters = pick_chapters(document_chapters)
        else:
            selected_chapters = find_good_chapters(document_chapters)
    print_selected_chapters(document_chapters, selected_chapters)
    texts = [c.extracted_text for c in selected_chapters]

    has_ffmpeg = shutil.which('ffmpeg') is not None
    if not has_ffmpeg:
        print('\033[91m' + 'ffmpeg not found. Please install ffmpeg to create mp3 and m4b audiobook files.' + '\033[0m')

    stats = SimpleNamespace(
        total_chars=sum(map(len, texts)),
        processed_chars=0,
        chars_per_sec=500 if torch.cuda.is_available() else 50,
        # For the ETA: real elapsed time since the whole job started, so
        # gen_audio_segments can revise chars_per_sec continuously (every
        # sentence) instead of only between chapters. That per-chapter
        # revision (_run_chapters_sequential/_run_chapters_parallel) left the
        # ETA stuck at this initial guess for as long as the *first* chapter
        # took -- minutes, for a long one -- which is what was actually
        # reported ("stuck on 1m 33s and 0%... a couple minutes").
        start_time=time.time())
    print('Started at:', time.strftime('%H:%M:%S'))
    print(f'Total characters: {stats.total_chars:,}')
    print('Total words:', len(' '.join(texts).split()))
    eta = strfdelta((stats.total_chars - stats.processed_chars) / stats.chars_per_sec)
    print(f'Estimated time remaining (assuming {stats.chars_per_sec} chars/sec): {eta}')

    # Tracks where every sentence lands in the final audio, for read-along
    # sync. Resolved from the engine actually converting this book, not the
    # module-level default: engines do not all use the same sample rate (see
    # audiblez.engines.engine_sample_rate).
    timeline = sync.SyncTimeline(engine_sample_rate(engine))

    # First pass, sequential and cheap: resolve which chapters are already done
    # (and can be spliced in from their cache, same as any resumed run) versus
    # which actually need synthesizing. Deciding this up front, before picking
    # a pipeline or a worker count, is what lets that decision skip chapters
    # that need no work at all.
    chapter_wav_files = []
    to_synthesize = []  # (i, chapter, text, chapter_wav_path)
    for i, chapter in enumerate(selected_chapters, start=1):
        if max_chapters and i > max_chapters: break
        text = chapter.extracted_text
        xhtml_file_name = chapter.get_name().replace(' ', '_').replace('/', '_').replace('\\', '_')
        chapter_wav_path = Path(output_folder) / filename.replace(extension, f'_chapter_{i}_{voice}_{xhtml_file_name}.wav')
        chapter_wav_files.append(chapter_wav_path)
        if Path(chapter_wav_path).exists():
            print(f'File for chapter {i} already exists. Skipping')
            stats.processed_chars += len(text)
            reuse_chapter_sync(timeline, chapter_wav_path, i, chapter, text)
            if post_event:
                post_event('CORE_CHAPTER_FINISHED', chapter_index=chapter.chapter_index)
            continue
        if len(text.strip()) < 10:
            print(f'Skipping empty chapter {i}')
            chapter_wav_files.remove(chapter_wav_path)
            continue
        if i == 1:
            # add intro text
            text = f'{title} – {creator}.\n\n' + text
        to_synthesize.append((i, chapter, text, chapter_wav_path))

    worker_count = resolve_worker_count(workers, len(to_synthesize))
    if worker_count > 1:
        _run_chapters_parallel(to_synthesize, voice, speed, max_sentences, worker_count, engine,
                                stats, post_event, timeline, chapter_wav_files)
    else:
        tts_engine = load_engine(engine, voice)
        _run_chapters_sequential(tts_engine, to_synthesize, voice, speed, max_sentences,
                                  stats, post_event, timeline, chapter_wav_files)

    sync_path = Path(output_folder) / filename.replace(extension, '.json')
    sync.write_sync_file(
        sync_path, timeline, title=title, author=creator,
        audio_file=filename.replace(extension, '.m4b'))
    print(f'Sync mapping written to {sync_path} ({len(timeline.chunks)} chunks, '
          f'{timeline.current_time:.1f}s)')

    if has_ffmpeg:
        create_index_file(title, creator, chapter_wav_files, output_folder)
        create_m4b(chapter_wav_files, filename, cover_image, output_folder,
                   duration_s=timeline.current_time)
        if post_event: post_event('CORE_FINISHED')


def resolve_worker_count(workers, num_chapters):
    """How many chapters to synthesize at once.

    Multiple *processes*, not threads: a chapter crosses spaCy, the
    espeak/phonemizer backend and torch, and process-level parallelism is what
    reliably keeps every core busy across all of that, not just whichever part
    happens to release the GIL.

    Skipped (returns 1) on a CUDA/ROCm GPU: a single accelerator does not
    parallelize across processes the way independent CPU cores do, and several
    processes fighting over one GPU's VRAM more easily hurts than helps.
    Sequential-but-GPU-accelerated is already the fast path there.
    """
    if torch.cuda.is_available():
        return 1
    if workers is None:
        cpu = os.cpu_count() or 1
        # Each worker process holds its own copy of the loaded TTS model plus
        # spaCy in memory -- measured at ~1.8GB RSS per process (with Kokoro,
        # the engine in use when this was measured; Pocket TTS is a similarly
        # sized ~100M-param model, not separately re-measured since) on the
        # reference machine (a 12-core/24-thread Ryzen AI handheld with 22GB
        # RAM) once loaded. Half the logical CPUs, capped at 6, is a starting
        # point that leaves room for the rest of the stack (Celery, Redis,
        # the API) rather than the number that would purely maximize CPU use;
        # override with an explicit `workers` (server: REEDD_CONVERSION_WORKERS)
        # once you've watched `free -h` and temperatures during a real run.
        workers = max(1, min(cpu // 2, 6))
    return max(1, min(workers, num_chapters))


# Set once per worker process by _init_chapter_worker, not passed as an argument:
# a loaded TTSEngine is not (and does not need to be) picklable across the
# process boundary that Pool tasks cross.
_worker_engine = None


def _init_chapter_worker(engine_name, voice, threads):
    """Runs once per worker process, before it picks up its first chapter."""
    global _worker_engine
    torch.set_num_threads(max(1, threads))
    _worker_engine = load_engine(engine_name, voice, threads=threads)


def _synthesize_chapter_worker(args):
    """Runs in a worker process: synthesize one whole chapter and write its
    .wav and sync cache to disk, exactly as a sequential run would.

    Only a small summary crosses back to the main process -- the audio itself
    never does, which is what keeps this cheap even for a long chapter. The
    main process picks the result back up via the same cache file a resumed
    run already reads (`reuse_chapter_sync`), so nothing about how a chapter's
    files reach the timeline needs to know they came from another process.
    """
    index, text, voice, speed, chapter_wav_path, max_sentences = args
    engine_sample_rate = _worker_engine.sample_rate
    local_timeline = sync.SyncTimeline(engine_sample_rate)
    local_timeline.begin_chapter(index)
    audio_segments = gen_audio_segments(
        _worker_engine, text, voice, speed, max_sentences=max_sentences, timeline=local_timeline)
    if not audio_segments:
        return {'index': index, 'ok': False}
    final_audio = np.concatenate(audio_segments)
    soundfile.write(chapter_wav_path, final_audio, engine_sample_rate)
    _, relative_chunks = local_timeline.end_chapter()
    sync.save_chapter_sync(chapter_wav_path, len(final_audio) / engine_sample_rate, relative_chunks)
    return {'index': index, 'ok': True, 'chars': len(text)}


def _run_chapters_parallel(to_synthesize, voice, speed, max_sentences, workers, engine,
                            stats, post_event, timeline, chapter_wav_files):
    """Fans chapters out across a process pool.

    Uses `billiard` (Celery's own maintained fork of `multiprocessing`, and
    already a Celery dependency) rather than stdlib `multiprocessing` /
    `concurrent.futures.ProcessPoolExecutor`. When this runs inside a Celery
    worker (`--pool=prefork`, the reedd server's setup), the task-executing
    process is itself a *daemonic* child process, and stdlib multiprocessing
    refuses to let a daemonic process spawn children of its own
    ("daemonic processes are not allowed to have children") -- confirmed by
    hitting exactly that AssertionError through the real worker queue. billiard
    keeps its own separate process bookkeeping, precisely so Celery's own
    internals (and code that runs inside a Celery worker, like this) can do
    this without hitting that restriction.
    """
    print(f'Synthesizing {len(to_synthesize)} chapters across {workers} worker processes')
    threads_per_worker = max(1, (os.cpu_count() or workers) // workers)
    args_list = [
        (i, text, voice, speed, str(chapter_wav_path), max_sentences)
        for i, _chapter, text, chapter_wav_path in to_synthesize
    ]
    # stats.chars_per_sec starts out as a single-process guess (main()'s 500
    # chars/sec on CUDA, 50 on CPU), but here that guess is off by roughly
    # `workers`x once real throughput exists to replace it with -- otherwise
    # the ETA this reports would stay several times too pessimistic for the
    # whole run, undoing exactly what parallelizing was for. (This path never
    # runs on CUDA in practice -- resolve_worker_count() falls back to one
    # process whenever it is available -- but the revision below is what keeps
    # this path's own ETA honest regardless.)
    start_time = time.time()
    with billiard.pool.Pool(
        processes=workers, initializer=_init_chapter_worker, initargs=(engine, voice, threads_per_worker),
    ) as pool:
        # pool.imap() yields results in the order args_list was given, not
        # completion order -- every worker keeps running in the background
        # regardless of which result we are waiting on. That is what lets the
        # timeline (and the final .m4b's chapter order) be assembled strictly
        # in chapter order with no extra bookkeeping here, whichever chapter
        # actually finishes first.
        results = pool.imap(_synthesize_chapter_worker, args_list)
        for (i, chapter, text, chapter_wav_path), result in zip(to_synthesize, results):
            if not result['ok']:
                print(f'Warning: No audio generated for chapter {i}')
                chapter_wav_files.remove(chapter_wav_path)
                continue
            stats.processed_chars += result['chars']
            stats.progress = stats.processed_chars * 100 // stats.total_chars
            elapsed = time.time() - start_time
            if elapsed > 0:
                stats.chars_per_sec = stats.processed_chars / elapsed
            stats.eta = strfdelta((stats.total_chars - stats.processed_chars) / stats.chars_per_sec)
            if post_event:
                post_event('CORE_PROGRESS', stats=stats)
            print('Chapter written to', chapter_wav_path)
            reuse_chapter_sync(timeline, chapter_wav_path, i, chapter, text)
            if post_event:
                post_event('CORE_CHAPTER_FINISHED', chapter_index=chapter.chapter_index)


def _run_chapters_sequential(engine, to_synthesize, voice, speed, max_sentences,
                              stats, post_event, timeline, chapter_wav_files):
    # stats.chars_per_sec/eta are revised continuously, every sentence, inside
    # gen_audio_segments (from stats.start_time) -- nothing to redo here.
    for i, chapter, text, chapter_wav_path in to_synthesize:
        start_time = time.time()
        if post_event: post_event('CORE_CHAPTER_STARTED', chapter_index=chapter.chapter_index)
        timeline.begin_chapter(i, title=f'Chapter {i}', source=chapter.get_name())
        audio_segments = gen_audio_segments(
            engine, text, voice, speed, stats, post_event=post_event, max_sentences=max_sentences,
            timeline=timeline)
        if audio_segments:
            final_audio = np.concatenate(audio_segments)
            soundfile.write(chapter_wav_path, final_audio, engine.sample_rate)
            _, relative_chunks = timeline.end_chapter()
            sync.save_chapter_sync(chapter_wav_path, len(final_audio) / engine.sample_rate, relative_chunks)
            delta_seconds = time.time() - start_time
            chars_per_sec = len(text) / delta_seconds
            print('Chapter written to', chapter_wav_path)
            if post_event: post_event('CORE_CHAPTER_FINISHED', chapter_index=chapter.chapter_index)
            print(f'Chapter {i} read in {delta_seconds:.2f} seconds ({chars_per_sec:.0f} characters per second)')
        else:
            print(f'Warning: No audio generated for chapter {i}')
            timeline.end_chapter()
            chapter_wav_files.remove(chapter_wav_path)


def wav_duration(path):
    """Exact duration in seconds of an already-written chapter .wav."""
    try:
        return soundfile.info(str(path)).duration
    except Exception:
        # soundfile can't read it for some reason; ffprobe is the fallback.
        return probe_duration(str(path))


def reuse_chapter_sync(timeline, chapter_wav_path, index, chapter, text):
    """Advance the timeline over a chapter whose .wav was generated by an earlier run.

    The audio is not re-synthesised, so its sentence timings have to come from
    the cache written alongside the .wav. Without that cache we still have to
    advance by the chapter's real duration, otherwise every later chapter would
    be reported at the wrong time -- we just fall back to one coarse chunk
    covering the whole chapter.
    """
    duration = wav_duration(chapter_wav_path)
    relative_chunks = sync.load_chapter_sync(chapter_wav_path)
    if relative_chunks is None:
        print(f'Warning: no sync cache for chapter {index}; its text will be highlighted '
              f'as a single block. Delete {chapter_wav_path} and re-run for sentence-level sync.')
        relative_chunks = [{'text': text, 'start': 0.0, 'end': duration}]
    timeline.add_cached_chapter(
        index, duration, relative_chunks, title=f'Chapter {index}', source=chapter.get_name())


def find_cover(book):
    def is_image(item):
        return item is not None and item.media_type.startswith('image/')

    for item in book.get_items_of_type(ebooklib.ITEM_COVER):
        if is_image(item):
            return item

    # https://idpf.org/forum/topic-715
    for meta in book.get_metadata('OPF', 'cover'):
        if is_image(item := book.get_item_with_id(meta[1]['content'])):
            return item

    if is_image(item := book.get_item_with_id('cover')):
        return item

    for item in book.get_items_of_type(ebooklib.ITEM_IMAGE):
        if 'cover' in item.get_name().lower() and is_image(item):
            return item

    return None


def print_selected_chapters(document_chapters, chapters):
    ok = 'X' if platform.system() == 'Windows' else '✅'
    print(tabulate([
        [i, c.get_name(), len(c.extracted_text), ok if c in chapters else '', chapter_beginning_one_liner(c)]
        for i, c in enumerate(document_chapters, start=1)
    ], headers=['#', 'Chapter', 'Text Length', 'Selected', 'First words']))

def gen_audio_segments(engine, text, voice, speed, stats=None, max_sentences=None, post_event=None,
                       timeline=None):
    """Synthesise `text` sentence by sentence, via the given TTSEngine.

    Returns the raw audio segments. If a `sync.SyncTimeline` is passed, every
    sentence is also recorded on it with the exact timestamps it occupies in the
    output audio, for read-along highlighting.

    Splitting into sentences happens here, once, for every engine -- any
    further splitting a specific backend needs (Pocket TTS's own
    conservative-generation-settings workaround for a long sentence, for
    instance -- see PocketTTSEngine) is that engine's own concern, inside its
    `synthesize()`. Uses the quote-aware splitter in quote_split.py rather
    than a plain split, since a plain split would produce different sentence
    boundaries around dialogue in this kind of prose.
    """
    audio_segments = []
    sentences = [sentence for _kind, sentence in split_into_spans(text)]

    for i, sent_text in enumerate(sentences):
        if max_sentences and i > max_sentences: break
        # An engine can split one sentence into several segments; they are
        # contiguous in the output, so the sentence's duration is the sum of
        # their frames.
        sentence_frames = 0
        for audio in engine.synthesize(sent_text, voice, speed):
            audio_segments.append(audio)
            sentence_frames += sync.num_frames(audio)
        if timeline is not None and sentence_frames:
            timeline.add_chunk(sent_text, sentence_frames)
        if stats:
            stats.processed_chars += len(sent_text)
            stats.progress = stats.processed_chars * 100 // stats.total_chars
            # Revised every sentence from real elapsed time, not just between
            # chapters: the per-chapter revision alone left the ETA stuck at
            # main()'s initial guess for as long as the first chapter took to
            # finish -- minutes, for a long one. A couple of seconds' grace
            # before trusting it avoids one unusually fast/slow first sentence
            # swinging the estimate wildly.
            elapsed = time.time() - stats.start_time
            if elapsed > 2:
                stats.chars_per_sec = stats.processed_chars / elapsed
            stats.eta = strfdelta((stats.total_chars - stats.processed_chars) / stats.chars_per_sec)
            if post_event: post_event('CORE_PROGRESS', stats=stats)
            print(f'Estimated time remaining: {stats.eta}')
            print('Progress:', f'{stats.progress}%\n')
    return audio_segments


def gen_text(text, voice='alba', output_file='text.wav', speed=1, play=False, engine=DEFAULT_ENGINE):
    tts_engine = load_engine(engine, voice)
    load_spacy()
    timeline = sync.SyncTimeline(tts_engine.sample_rate)
    timeline.begin_chapter(1)
    audio_segments = gen_audio_segments(tts_engine, text, voice=voice, speed=speed, timeline=timeline)
    timeline.end_chapter()
    final_audio = np.concatenate(audio_segments)
    soundfile.write(output_file, final_audio, tts_engine.sample_rate)
    sync.write_sync_file(
        Path(output_file).with_suffix('.json'), timeline, audio_file=Path(output_file).name)
    if play:
        subprocess.run(['ffplay', '-autoexit', '-nodisp', output_file])


#: A paragraph ending in dialogue ("...you.") is already sentence-terminated,
#: but its *literal* last character is the closing quote mark, not the
#: period -- `text.endswith('.')` alone misses that entirely and would
#: append a second, orphaned period after the quote on every such paragraph.
#: That stray "." survives quote_split.py's own span-splitting as a
#: one-character narration span, which text_split.split_sentences() then
#: hands back as its own "sentence" (its only filter drops whitespace-only
#: fragments, not punctuation-only ones) -- one extra TTS chunk per
#: occurrence, synthesized and timed like any other, and in a
#: dialogue-heavy chapter this is not rare: confirmed against a real
#: chapter of "A Scandal in Bohemia" at 188 of that chapter's 962 chunks.
#: `[.!?]` optionally followed by one closing-quote character, anchored to
#: the end of the string, treats the paragraph as already terminated in
#: exactly that case. Also includes an em dash and a colon, each optionally
#: quote-closed, for the same reason but a different two cases confirmed in
#: that same chapter: interrupted dialogue ("But your client—” “Never mind
#: him.”" -- the em dash *is* the sentence's own ending, on purpose) and a
#: narration paragraph introducing a quoted letter ("...ran in this way:"),
#: neither of which wants a period tacked on either. (Residual gap, not
#: chased further: a quoted paragraph with no terminal punctuation *inside*
#: the quote at all -- "Well”" rather than "Well.”" -- still gets a
#: spurious period, since there is no `.!?—:` for the regex to find; rare
#: enough in practice not to be worth the added complexity of also
#: inspecting content inside the trailing quote.)
_SENTENCE_TERMINATED_RE = re.compile(r'[.!?—:][”’"\']?$')


def needs_terminal_period(text):
    """Whether `text` (one paragraph's worth) needs a period appended to
    read as a complete sentence to the downstream splitter -- see
    `_SENTENCE_TERMINATED_RE`'s own comment for why this isn't simply
    `not text.endswith('.')`."""
    return not _SENTENCE_TERMINATED_RE.search(text)


def find_document_chapters_and_extract_texts(book):
    """Returns every chapter that is an ITEM_DOCUMENT and enriches each chapter with extracted_text."""
    document_chapters = []
    for chapter in book.get_items():
        if chapter.get_type() != ebooklib.ITEM_DOCUMENT:
            continue
        xml = chapter.get_body_content()
        soup = BeautifulSoup(xml, features='lxml')
        chapter.extracted_text = ''
        html_content_tags = ['title', 'p', 'h1', 'h2', 'h3', 'h4', 'li']
        for text in [c.text.strip() for c in soup.find_all(html_content_tags) if c.text]:
            if needs_terminal_period(text):
                text += '.'
            chapter.extracted_text += text + '\n'
        document_chapters.append(chapter)
    for i, c in enumerate(document_chapters):
        c.chapter_index = i  # this is used in the UI to identify chapters
    return document_chapters


def is_chapter(c):
    name = c.get_name().lower()
    has_min_len = len(c.extracted_text) > 100
    title_looks_like_chapter = bool(
        'chapter' in name.lower()
        or re.search(r'part_?\d{1,3}', name)
        or re.search(r'split_?\d{1,3}', name)
        or re.search(r'ch_?\d{1,3}', name)
        or re.search(r'chap_?\d{1,3}', name)
    )
    return has_min_len and title_looks_like_chapter


def chapter_beginning_one_liner(c, chars=20):
    s = c.extracted_text[:chars].strip().replace('\n', ' ').replace('\r', ' ')
    return s + '…' if len(s) > 0 else ''


def find_good_chapters(document_chapters):
    chapters = [c for c in document_chapters if c.get_type() == ebooklib.ITEM_DOCUMENT and is_chapter(c)]
    if len(chapters) == 0:
        print('Not easy to recognize the chapters, defaulting to all non-empty documents.')
        chapters = [c for c in document_chapters if c.get_type() == ebooklib.ITEM_DOCUMENT and len(c.extracted_text) > 10]
    return chapters


def pick_chapters(chapters):
    # Display the document name, the length and first 50 characters of the text
    chapters_by_names = {
        f'{c.get_name()}\t({len(c.extracted_text)} chars)\t[{chapter_beginning_one_liner(c, 50)}]': c
        for c in chapters}
    title = 'Select which chapters to read in the audiobook'
    ret = pick(list(chapters_by_names.keys()), title, multiselect=True, min_selection_count=1)
    selected_chapters_out_of_order = [chapters_by_names[r[0]] for r in ret]
    selected_chapters = [c for c in chapters if c in selected_chapters_out_of_order]
    return selected_chapters


def strfdelta(tdelta, fmt='{D:02}d {H:02}h {M:02}m {S:02}s'):
    remainder = int(tdelta)
    f = Formatter()
    desired_fields = [field_tuple[1] for field_tuple in f.parse(fmt)]
    possible_fields = ('W', 'D', 'H', 'M', 'S')
    constants = {'W': 604800, 'D': 86400, 'H': 3600, 'M': 60, 'S': 1}
    values = {}
    for field in possible_fields:
        if field in desired_fields and field in constants:
            values[field], remainder = divmod(remainder, constants[field])
    return f.format(fmt, **values)


def has_ffmpeg_encoder(name):
    try:
        proc = subprocess.run(['ffmpeg', '-hide_banner', '-encoders'],
                              capture_output=True, text=True, check=True)
    except (subprocess.CalledProcessError, FileNotFoundError):
        return False
    return any(line.split()[1:2] == [name] for line in proc.stdout.splitlines())


def concat_wavs_with_ffmpeg(chapter_files, output_folder, filename):
    wav_list_txt = Path(output_folder) / filename.replace('.epub', '_wav_list.txt')
    with open(wav_list_txt, 'w') as f:
        for wav_file in chapter_files:
            # ffmpeg's concat demuxer resolves relative paths against the list
            # file's own directory, not the cwd, so these have to be absolute.
            f.write(f"file '{Path(wav_file).resolve()}'\n")
    concat_file_path = Path(output_folder) / filename.replace('.epub', '.tmp.wav')
    # Lossless (pcm), not AAC: this used to encode to AAC here and then
    # create_m4b encoded *again* to AAC for the final file -- two lossy passes
    # compounding artifacts for no benefit, since this file is deleted the
    # moment create_m4b finishes with it. One AAC encode, in create_m4b, is
    # enough.
    proc = subprocess.run([
        'ffmpeg', '-y', '-f', 'concat', '-safe', '0', '-i', wav_list_txt,
        '-c:a', 'pcm_s16le',
        concat_file_path])
    Path(wav_list_txt).unlink()
    if proc.returncode != 0 or not Path(concat_file_path).exists():
        # Otherwise this surfaces later as a confusing missing-file error.
        raise RuntimeError(f'ffmpeg failed to concatenate the chapter wavs (exit {proc.returncode})')
    return concat_file_path


#: Past this many hours of audio, a book is long enough that file size starts
#: to matter more than the small quality edge 64k has over 48k -- confirmed
#: by ear on a real chapter (see BUGS.md): 48k held up fine for narration and
#: cut a 40-hour book from ~1.2GB to under 900MB. Chosen over anything lower
#: (32k) because 48k was the one both bitrates below 64k that still sounded
#: clearly acceptable on that listen.
LARGE_BOOK_HOURS = 10

LARGE_BOOK_BITRATE = '48k'
DEFAULT_BITRATE = '64k'


def bitrate_for_duration(duration_s):
    """The AAC bitrate `create_m4b` should encode at, given the book's total
    synthesized audio duration in seconds (`timeline.current_time`, known
    once synthesis finishes and before `create_m4b` is called -- no need to
    guess from character count or wait for the finished file to measure it).

    `None` (duration not known, e.g. a caller that only has chapter files)
    falls back to the default -- being conservative about quality when the
    length is unknown, rather than guessing a book is large.
    """
    if duration_s is not None and duration_s >= LARGE_BOOK_HOURS * 3600:
        return LARGE_BOOK_BITRATE
    return DEFAULT_BITRATE


def create_m4b(chapter_files, filename, cover_image, output_folder, duration_s=None):
    concat_file_path = concat_wavs_with_ffmpeg(chapter_files, output_folder, filename)
    final_filename = Path(output_folder) / filename.replace('.epub', '.m4b')
    chapters_txt_path = Path(output_folder) / "chapters.txt"
    print('Creating M4B file...')

    if cover_image:
        cover_file_path = Path(output_folder) / 'cover'
        with open(cover_file_path, 'wb') as f:
            f.write(cover_image)
        cover_image_args = [
            '-i', f'{cover_file_path}',
            '-map', '2:v',  # Map cover image
            '-disposition:v', 'attached_pic',  # Ensure cover is embedded
            '-c:v', 'copy',  # Keep cover unchanged
        ]
    else:
        cover_image_args = []

    proc = subprocess.run([
        'ffmpeg',
        '-y',  # Overwrite output
        
        '-i', f'{concat_file_path}',  # Input audio
        '-i', f'{chapters_txt_path}',  # Input chapters
        *cover_image_args,  # Cover image (if provided)

        '-map', '0:a',  # Map audio
        # Voices vary wildly in raw loudness -- measured live across the
        # Pocket TTS roster: peter_yearsley sits at -30.8 LUFS, george at
        # -20.1, mary at -19.7, roughly a 10 LU gap, easily audible as
        # "quiet even at max phone volume" for the quiet end of that range.
        # Nothing upstream (none of the three engines) applies any gain of
        # its own -- see engines.py -- so this is the one point after
        # synthesis every book/voice/engine passes through exactly once.
        # -18 LUFS integrated is a conventional audiobook target (in the
        # neighborhood of Audible's own -18 to -23 LUFS spec); -2 dBTP true
        # peak leaves headroom for the AAC encode step right after this
        # filter to not clip on its own rounding.
        '-af', 'loudnorm=I=-18:TP=-2:LRA=11',
        # libfdk_aac is absent from most distro ffmpeg builds (not
        # GPL-compatible); the native encoder is the fallback.
        '-c:a', 'libfdk_aac' if has_ffmpeg_encoder('libfdk_aac') else 'aac',
        # The only lossy encode now (concat above is pcm) -- a since-removed
        # intermediate pass used to make 48k noticeably worse than today's
        # single-pass 48k, which is why 64k was the default for a while.
        # 64k mono, single-pass, was chosen over 96k/128k for the smaller
        # file size -- ffmpeg's native aac encoder self-limits mono 24kHz
        # audio to roughly 90-96kbps regardless of a higher request anyway
        # (measured: 96k and 128k both landed at ~96kbps actual), so 96k+
        # buys clarity headroom this content mostly doesn't use. See
        # `bitrate_for_duration` for when a long book drops to 48k instead.
        '-b:a', bitrate_for_duration(duration_s),

        '-map_metadata', '1', # Map metadata

        '-f', 'mp4',  # Output as M4B
        f'{final_filename}'  # Output file
    ])

    Path(concat_file_path).unlink()
    if proc.returncode == 0:
        print(f'{final_filename} created. Enjoy your audiobook.')
        print('Feel free to delete the intermediary .wav chapter files, the .m4b is all you need.')


def probe_duration(file_name):
    args = ['ffprobe', '-i', file_name, '-show_entries', 'format=duration', '-v', 'quiet', '-of', 'default=noprint_wrappers=1:nokey=1']
    proc = subprocess.run(args, capture_output=True, text=True, check=True)
    return float(proc.stdout.strip())


def create_index_file(title, creator, chapter_mp3_files, output_folder):
    with open(Path(output_folder) / "chapters.txt", "w", encoding="utf-8") as f:
        f.write(f";FFMETADATA1\ntitle={title}\nartist={creator}\n\n")
        start = 0
        i = 0
        for c in chapter_mp3_files:
            duration = probe_duration(c)
            end = start + (int)(duration * 1000)
            f.write(f"[CHAPTER]\nTIMEBASE=1/1000\nSTART={start}\nEND={end}\ntitle=Chapter {i}\n\n")
            i += 1
            start = end


def unmark_element(element, stream=None):
    """auxiliarry function to unmark markdown text"""
    if stream is None:
        stream = StringIO()
    if element.text:
        stream.write(element.text)
    for sub in element:
        unmark_element(sub, stream)
    if element.tail:
        stream.write(element.tail)
    return stream.getvalue()


def unmark(text):
    """Unmark markdown text"""
    Markdown.output_formats["plain"] = unmark_element  # patching Markdown
    __md = Markdown(output_format="plain")
    __md.stripTopLevelTags = False
    return __md.convert(text)
