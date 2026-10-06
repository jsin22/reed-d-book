package dev.reedd.domain

import java.text.Normalizer

/**
 * The author's surname, for sorting: the last word of the name,
 * skipping generational suffixes ("Martin Luther King Jr." -> King), or the part
 * before a comma when the name is already written surname-first
 * ("Stevenson, Robert Louis"). For several authors ("A and B", "A & B",
 * "A; B"), the first one's. Null for a missing or blank author.
 */
fun authorSurname(author: String?): String? {
    val first = author?.split(Regex("""\s+(?:and|&)\s+|;"""))?.firstOrNull()?.trim()
    if (first.isNullOrEmpty()) return null
    if (',' in first) {
        val beforeComma = first.substringBefore(',').trim()
        // "King, Jr." style: the part before the comma is the whole name, not a surname.
        if (beforeComma.contains(' ') && first.substringAfter(',').trim().trimEnd('.').lowercase() in SUFFIXES) {
            return surnameFromWords(beforeComma)
        }
        if (beforeComma.isNotEmpty()) return beforeComma
    }
    return surnameFromWords(first)
}

private val SUFFIXES = setOf("jr", "sr", "ii", "iii", "iv", "phd", "md")

private fun surnameFromWords(name: String): String? {
    val words = name.split(Regex("\\s+")).map { it.trim(',') }.filter { it.isNotEmpty() }
    return words.lastOrNull { it.trimEnd('.').lowercase() !in SUFFIXES } ?: words.lastOrNull()
}

/** Sort key for Author A-Z: surname first, then the full name to break ties. */
fun authorSortKey(author: String?): String? =
    authorSurname(author)?.let { "${foldAccents(it).lowercase()} ${foldAccents(author!!).lowercase()}" }

private fun foldAccents(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
