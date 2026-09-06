package dev.reedd.data.db

/**
 * The four bookmark colors. Deliberately no built-in meaning ("Favorite
 * Quotes", etc.) -- what each color is *for* is entirely up to the reader,
 * edited on the Bookmark colors settings screen and persisted in
 * [dev.reedd.data.settings.SettingsStore.bookmarkLabels], not baked in here.
 * A fresh install shows all four with no label at all, just their color.
 *
 * The actual `Color` each renders as lives in the UI layer
 * ([dev.reedd.ui.reader.color]), the same separation [DownloadState]/
 * [dev.reedd.data.remote.JobStatus] already keep from their own UI treatment.
 */
enum class BookmarkType {
    FAVORITE_QUOTE,
    CRUCIAL_PLOT,
    NEEDS_REVIEW,
    DEFAULT,
}
