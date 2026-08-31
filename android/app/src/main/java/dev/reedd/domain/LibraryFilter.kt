package dev.reedd.domain

import dev.reedd.data.db.BookEntity

/**
 * The library screen's filter state -- two independent facets, not a single
 * "group by" (see `SORT_GROUP_LIBRARY.md` for why: a book can honestly belong
 * to more than one genre at once, which a group-by cannot represent).
 *
 * @param category single-valued: a book is Fiction, Non-fiction, or Unknown
 *   ([BookEntity.category] is null), never more than one. Null here means
 *   "no category filter applied", not "filter to Unknown".
 * @param genres multi-valued and OR'd together: selecting Horror and Romance
 *   shows a book that matches either, since [BookEntity.genres] itself can
 *   hold more than one tag. Empty means "no genre filter applied".
 */
data class LibraryFilter(
    val category: String? = null,
    val genres: Set<String> = emptySet(),
) {
    val isActive: Boolean get() = category != null || genres.isNotEmpty()
}

/**
 * Pure, unit-tested independently of Compose/Room/network -- see
 * `LibraryFilterTest.kt`. Category and genre are AND'd together; within
 * genre, a book need only match one of the selected tags (OR).
 */
fun List<BookEntity>.filteredBy(state: LibraryFilter): List<BookEntity> = filter { book ->
    (state.category == null || book.category == state.category) &&
        (state.genres.isEmpty() || book.genres.any { it in state.genres })
}

/** Every category present in the library today, for the filter sheet's
 *  options -- alphabetical, and never includes null/Unknown as a choice since
 *  there is nothing to select there beyond clearing the filter. */
fun List<BookEntity>.availableCategories(): List<String> = mapNotNull { it.category }.distinct().sorted()

/** Every genre present in the library today, same reasoning as
 *  [availableCategories]. */
fun List<BookEntity>.availableGenres(): List<String> = flatMap { it.genres }.distinct().sorted()
