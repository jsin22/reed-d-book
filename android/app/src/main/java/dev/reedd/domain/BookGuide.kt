package dev.reedd.domain

import dev.reedd.data.remote.GuideChapterDto
import dev.reedd.data.remote.GuideCharacterDto
import dev.reedd.data.remote.GuideDto

/**
 * What of the book guide the reader may see from where they are. The rule is
 * the same everywhere: **only chapters already finished** -- the current one is
 * in progress, and its guide entry describes it to its end, so showing it
 * would spoil the rest of the chapter. Pure, unit-tested in `BookGuideTest.kt`.
 */
object BookGuideView {

    /** Index of the guide chapter for a Readium resource href, or null. */
    fun chapterIndexOf(guide: GuideDto, resourceHref: String?): Int? {
        val name = bareResourceName(resourceHref) ?: return null
        return guide.chapters.indexOfFirst { bareResourceName(it.source) == name }.takeIf { it >= 0 }
    }

    /** Recaps of every finished chapter before [currentIndex] that has one. */
    fun storySoFar(guide: GuideDto, currentIndex: Int): List<GuideChapterDto> =
        guide.chapters.take(currentIndex.coerceAtLeast(0))
            .filter { it.status == "done" && it.summary.isNotBlank() }

    /**
     * Characters as of the end of the chapter before [currentIndex], merged
     * across chapters the same way the server merges them
     * (`book_guide.known_characters`): on name *or* alias, since a book does not
     * name people consistently, the latest description winning, the name the
     * book first used kept as the display name.
     */
    fun charactersSoFar(guide: GuideDto, currentIndex: Int): List<GuideCharacterDto> {
        val merged = mutableListOf<GuideCharacterDto>()
        for (chapter in guide.chapters.take(currentIndex.coerceAtLeast(0))) {
            if (chapter.status != "done") continue
            for (c in chapter.characters) {
                val labels = (listOf(c.name) + c.aliases).map { it.lowercase() }.toSet()
                val at = merged.indexOfFirst { m -> (listOf(m.name) + m.aliases).any { it.lowercase() in labels } }
                if (at < 0) {
                    merged += c
                    continue
                }
                val existing = merged[at]
                val aliases = existing.aliases.toMutableList()
                for (alias in listOf(c.name) + c.aliases) {
                    if (!alias.equals(existing.name, ignoreCase = true) && aliases.none { it.equals(alias, ignoreCase = true) }) {
                        aliases += alias
                    }
                }
                merged[at] = existing.copy(
                    aliases = aliases,
                    description = c.description.ifBlank { existing.description },
                )
            }
        }
        return merged
    }

    /** Whether every chapter has been built -- an incomplete guide is worth
     *  fetching again later. */
    fun isComplete(guide: GuideDto): Boolean = guide.chapters.isNotEmpty() && guide.chapters.all { it.status == "done" }
}
