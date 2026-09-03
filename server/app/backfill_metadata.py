# -*- coding: utf-8 -*-
"""One-off maintenance: category/genre for jobs converted before the app
started sending title/author on upload (see SORT_GROUP_LIBRARY.md, "Known
limitation: existing books"). Every job since then already carries both and
gets looked up automatically from `create_job`'s BackgroundTasks -- this is
only for the backlog that predates that.

Reads title/author straight out of each job's own stored epub (`epub_meta.
read_title_author`) rather than the sanitised upload filename, which is
normalised into underscores and often missing the author entirely. A job
whose epub is gone, or whose OPF metadata has no title, is skipped and
reported rather than guessed at.

`--recheck` re-runs a job that already has *some* category/genres too, and
bypasses the cache -- for re-enriching books that were "resolved" under an
older lookup (Ollama, then Open Library/Google Books, now Gemini -- see
LLM_GENRE_ENRICHMENT.md) before the current one existed: a plain re-run
would otherwise skip exactly those, since having any category/genres at
all already counts as resolved.

    python -m app.backfill_metadata              # writes the results
    python -m app.backfill_metadata --dry-run     # prints what it would do
    python -m app.backfill_metadata --recheck     # re-enrich already-resolved jobs too
"""

import logging

from .book_metadata import LookupUnavailable, lookup
from .book_metadata_store import BookMetadataStore
from .config import get_settings
from .epub_meta import read_title_author
from .store import JobNotFound, JobStore

log = logging.getLogger('reedd.backfill_metadata')


def backfill(dry_run: bool = False, recheck: bool = False) -> None:
    settings = get_settings()
    jobs = JobStore(settings.jobs_dir)
    cache = BookMetadataStore(settings.data_dir)

    updated = skipped = unavailable = 0
    for manifest in jobs.list(limit=10_000):
        job_id = manifest['job_id']
        # Already resolved (including a genuine "nothing found", which is
        # category=None but genres=[] -- both fields absent is the only
        # "never looked up" state) -- re-running would just repeat the same
        # external lookups for nothing, unless --recheck asked for exactly
        # that (to pick up genre tags an enrichment pass added since).
        if not recheck and (manifest.get('category') is not None or manifest.get('genres')):
            continue

        title, author = manifest.get('title'), manifest.get('author')
        if not title:
            epub_path = jobs.job_dir(job_id) / manifest['filename']
            if not epub_path.is_file():
                print(f'{job_id}: epub missing ({epub_path.name}), skipping')
                skipped += 1
                continue
            title, author = read_title_author(epub_path)
        if not title:
            print(f'{job_id}: no title in {manifest["filename"]!r} either way, skipping')
            skipped += 1
            continue

        cached = None if recheck else cache.get(title, author)
        if cached is None:
            try:
                result = lookup(title, author)
            except LookupUnavailable as e:
                print(f'{job_id}: {title!r} -- lookup unavailable, try again later ({e})')
                unavailable += 1
                continue
            cache.put(title, author, result)
            cached = cache.get(title, author)

        print(f'{job_id}: {title!r} by {author!r} -> {cached["category"]}, {cached["genres"]}')
        if not dry_run:
            try:
                jobs.update(job_id, title=title, author=author,
                            category=cached['category'], genres=cached['genres'])
            except JobNotFound:
                continue  # deleted while this was running
        updated += 1

    verb = 'would update' if dry_run else 'updated'
    print(f'done: {verb} {updated}, skipped {skipped}, unavailable {unavailable}')


if __name__ == '__main__':
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dry-run', action='store_true',
                         help='print what would change without writing anything')
    parser.add_argument('--recheck', action='store_true',
                         help='re-run already-resolved jobs too, bypassing the cache')
    args = parser.parse_args()
    backfill(dry_run=args.dry_run, recheck=args.recheck)
