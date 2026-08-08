package dev.reedd.data.align

import org.jsoup.Jsoup
import java.io.File
import java.util.zip.ZipFile

/**
 * Pulls each XHTML resource's text out of an epub.
 *
 * Reads the zip directly rather than going through Readium. Two reasons: this runs
 * in a background worker where opening a whole `Publication` to read raw text is
 * more machinery than the job needs, and doing it here keeps [ChunkAligner]'s
 * input a plain map that tests can supply without an Android runtime.
 *
 * `wholeText()`, not `text()`. jsoup's `text()` collapses whitespace, but Readium
 * resolves a locator by searching the rendered DOM's text content, which keeps the
 * whitespace the author wrote. Storing the collapsed form would mean handing
 * Readium a string that is not on the page. [TextNormalizer] does the forgiving
 * comparison instead, and the stored substring stays DOM-faithful.
 */
object EpubTextExtractor {

    /** Extensions worth reading; everything else in an epub is not prose. */
    private val TEXT_SUFFIXES = listOf(".xhtml", ".html", ".htm", ".xml")

    fun extract(epub: File): List<ResourceText> = ZipFile(epub).use { zip ->
        zip.entries().asSequence()
            .filter { entry -> !entry.isDirectory && TEXT_SUFFIXES.any { entry.name.lowercase().endsWith(it) } }
            .mapNotNull { entry ->
                runCatching {
                    val html = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
                    val body = Jsoup.parse(html).body() ?: return@runCatching null
                    ResourceText(href = entry.name, text = body.wholeText())
                }.getOrNull()
            }
            .filter { it.text.isNotBlank() }
            .toList()
    }
}
