package dev.reedd.data.dictionary

/**
 * Turns a word as it appears on the page into forms a dictionary might list.
 *
 * A reader taps "nodded", "children" or "running"; the dictionary holds "nod",
 * "child" and "run". Two mechanisms, in order:
 *
 *  * **irregular forms**, from WordNet's own exception lists, which is the only way
 *    to get "went" to "go" — no rule will do it;
 *  * **suffix rules**, WordNet's Morphy detachment rules, for the regular cases.
 *
 * Candidates are returned in order of likelihood rather than resolved outright: the
 * caller tries each against the database and takes the first that exists, which
 * avoids guessing wrong when a rule and a real word collide ("buses" -> "buse" is
 * offered, but "bus" is offered first).
 */
object Lemmatizer {

    /**
     * WordNet's detachment rules: suffix to strip, and what to put back.
     * Order matters — longer, more specific suffixes first.
     */
    private val RULES = listOf(
        "ches" to "ch", "shes" to "sh", "sses" to "ss", "xes" to "x", "zes" to "z",
        "ies" to "y", "ves" to "f",
        "men" to "man",
        "ing" to "", "ing" to "e",
        "ied" to "y",
        "ed" to "", "ed" to "e",
        "es" to "", "es" to "e",
        "s" to "",
        "er" to "", "er" to "e",
        "est" to "", "est" to "e",
        "ly" to "",
    )

    /**
     * Cleans a tapped word: strips surrounding punctuation and quotes but keeps
     * internal apostrophes and hyphens, which are part of words like "don't" and
     * "well-known".
     */
    fun normalize(raw: String): String =
        raw.trim()
            .trim('"', '“', '”', '‘', '’', '(', ')', '[', ']',
                  '.', ',', ';', ':', '!', '?', '—', '–', '-', '…', '*')
            .lowercase()

    /**
     * Forms to try, most likely first. Always starts with the word itself.
     *
     * @param irregular the exception-list base for this word, if the database has
     *   one. Placed immediately after the literal form, ahead of any rule.
     */
    fun candidates(word: String, irregular: String? = null): List<String> {
        val base = normalize(word)
        if (base.isEmpty()) return emptyList()

        val out = LinkedHashSet<String>()
        out += base
        irregular?.let { out += it }

        // A possessive is not a different word.
        if (base.endsWith("'s") || base.endsWith("’s")) {
            out += base.dropLast(2)
        }

        for ((suffix, replacement) in RULES) {
            if (base.length > suffix.length + 1 && base.endsWith(suffix)) {
                out += base.dropLast(suffix.length) + replacement
            }
        }

        // Doubled consonant before -ing/-ed: "nodded" -> "nod", "running" -> "run".
        for (suffix in listOf("ing", "ed")) {
            if (base.length > suffix.length + 2 && base.endsWith(suffix)) {
                val stem = base.dropLast(suffix.length)
                if (stem.length >= 3 && stem[stem.length - 1] == stem[stem.length - 2]) {
                    out += stem.dropLast(1)
                }
            }
        }
        return out.filter { it.isNotEmpty() }
    }
}
