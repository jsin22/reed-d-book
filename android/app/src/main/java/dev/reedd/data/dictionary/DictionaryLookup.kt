package dev.reedd.data.dictionary

import dev.reedd.data.remote.ApiException
import dev.reedd.data.remote.ApiProvider
import dev.reedd.data.remote.DefinitionDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

/** What a lookup came back with -- including *why* there is no definition. */
sealed interface LookupResult {
    data class Found(val definition: Definition) : LookupResult
    /** Neither the bundled nor the full dictionary has the word. */
    data object NotFound : LookupResult
    /** Not in the bundled dictionary, and the server could not answer. */
    data object Unreachable : LookupResult
}

/**
 * Definitions from the bundled dictionary first -- instant and offline, and
 * where nearly every lookup ends -- then, only for a word it does not have,
 * the server's full one (E-8): rare words a reader actually wants defined,
 * which the bundled copy's frequency cutoff dropped. Nothing fetched is kept
 * on the phone; a rare word asks the server each time.
 *
 * @param remote the server call, a seam for tests; defaults to [ApiProvider].
 */
class DictionaryLookup(
    private val local: suspend (String) -> Definition?,
    private val remote: suspend (word: String, candidates: List<String>) -> DefinitionDto,
) {
    constructor(dictionary: Dictionary, api: ApiProvider) : this(
        local = dictionary::lookup,
        remote = { word, candidates -> api.service().define(word, candidates) },
    )

    suspend fun lookup(rawWord: String): LookupResult {
        runCatching { local(rawWord) }.getOrNull()?.let { return LookupResult.Found(it) }

        val word = Lemmatizer.normalize(rawWord)
        if (word.isEmpty()) return LookupResult.NotFound
        // The server adds its own irregular-form entry; these are the suffix
        // rules, which live here only, so both dictionaries agree on them.
        val candidates = Lemmatizer.candidates(word).drop(1)
        return try {
            val dto = withTimeout(REMOTE_TIMEOUT_MS) { remote(word, candidates) }
            LookupResult.Found(
                Definition(
                    queried = dto.queried,
                    word = dto.word,
                    senses = dto.senses.map { Sense(it.partOfSpeech, it.definition, it.synonyms, it.pronunciation) },
                ),
            )
        } catch (e: ApiException) {
            // 404: the full dictionary does not have it either -- a real answer.
            if (e.isNotFound) LookupResult.NotFound else LookupResult.Unreachable
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) LookupResult.Unreachable else throw e
        } catch (e: Exception) {
            LookupResult.Unreachable // no network, no server configured, a dropped connection
        }
    }

    companion object {
        /** Long enough for a slow mobile connection, short enough to give up visibly. */
        const val REMOTE_TIMEOUT_MS = 8_000L
    }
}
