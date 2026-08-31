package dev.reedd.domain

import dev.reedd.data.db.BookEntity

/** How the library screen orders its cards. Persisted by name (see
 *  `SettingsStore.LibraryViewSettings`), so renaming a constant here would
 *  silently reset every device's saved choice back to the default. */
enum class LibrarySort {
    TITLE_ASC, AUTHOR_ASC, RECENTLY_ADDED, RECENTLY_OPENED,
}

/**
 * Pure, unit-tested independently of Compose/Room/network -- see
 * `LibrarySortTest.kt` and `SORT_GROUP_LIBRARY.md`.
 *
 * Named `librarySorted`, not `sortedBy`: an extension called `sortedBy` here
 * would sit right next to the stdlib's own `sortedBy`/`sorted` on every
 * `List<BookEntity>`, and callers picking the wrong one by autocomplete would
 * fail silently rather than not compile.
 */
fun List<BookEntity>.librarySorted(sort: LibrarySort): List<BookEntity> = when (sort) {
    LibrarySort.TITLE_ASC -> sortedBy { it.title.lowercase() }
    // A book with no author sorts after every named one, rather than grouping
    // at the front the way a plain `sortedBy { it.author }` would (Kotlin
    // treats null as less than any string).
    LibrarySort.AUTHOR_ASC -> sortedWith(compareBy<BookEntity> { it.author == null }.thenBy { it.author?.lowercase() })
    LibrarySort.RECENTLY_ADDED -> sortedByDescending { it.addedAt }
    // Same "never happened sorts last" reasoning as AUTHOR_ASC, just descending:
    // a book that has never been opened has no lastOpenedAt to rank by at all,
    // and does not belong ahead of one opened long ago.
    LibrarySort.RECENTLY_OPENED -> sortedWith(
        compareByDescending<BookEntity> { it.lastOpenedAt != null }.thenByDescending { it.lastOpenedAt ?: 0L }
    )
}
