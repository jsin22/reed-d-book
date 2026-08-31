package dev.reedd.data.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.github.luben.zstd.ZstdInputStream
import dev.reedd.diagnostics.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One sense of a word, and a few words that mean roughly the same thing in that sense. */
data class Sense(
    val partOfSpeech: String,
    val definition: String,
    val synonyms: List<String> = emptyList(),
    val pronunciation: String? = null,
)

/** What a lookup found. [word] is the form actually matched, which may be a lemma. */
data class Definition(
    val queried: String,
    val word: String,
    val senses: List<Sense>,
) {
    /** The word's own IPA pronunciation, if the dictionary has one for it — the
     *  same for every sense here in practice, so shown once rather than per sense. */
    val pronunciation: String? get() = senses.firstNotNullOfOrNull { it.pronunciation }
}

/**
 * The bundled offline dictionary: a filtered extract of Wiktionary, shipped in the
 * APK. See dictionary-LICENSE.txt for attribution and license terms.
 *
 * Offline by requirement — looking a word up while reading should not depend on a
 * network, and this app is used with a server that may be on the other side of the
 * house or not running at all.
 *
 * Was WordNet 3.0 through [ASSET_VERSION] 1: WordNet is a lexical-semantic database
 * of content words, not a general dictionary, so it has no entry at all for "the"
 * and a handful of other function words, and (worse) an entry for "he" resolves to
 * a rare noun sense for the chemical symbol of helium, with no pronoun sense to
 * outrank it — WordNet has no part of speech for pronouns, articles, prepositions,
 * or conjunctions at all. Wiktionary is a real general dictionary and covers all of
 * those properly; see tools/build_dictionary_wiktionary.py for how the shipped
 * database is built from it.
 *
 * The database is an asset, so it has to be copied out before SQLite can open it
 * (assets live compressed inside the APK and have no file path). The copy is done
 * once and keyed by [ASSET_VERSION], so shipping a new database replaces the old one
 * rather than silently keeping it.
 *
 * The asset itself is zstd-compressed (`dictionary.db.zst`, decompressed by
 * [extractIfNeeded] via zstd-jni), not the raw `.db` — an experiment prompted by
 * wanting the APK smaller than AAPT's own deflate got it. zstd -19 shrinks the
 * 20.4 MB database to about 7.1 MB (roughly a third the size, vs. deflate's ~50%),
 * at the cost of one native library (~600 KB, arm64-v8a only — see the `ndk`
 * block in app/build.gradle.kts) and one decompression pass on first use, timed
 * and reported via [CrashReporter.reportDiagnostic] for now while this is still
 * being measured on a real device rather than guessed at.
 */
class Dictionary(
    private val context: Context,
    /**
     * Test-only escape hatch: when set, [extractIfNeeded] returns this file
     * directly and skips real zstd decompression entirely. zstd-jni can't
     * actually run under Robolectric -- confirmed by reproducing the same
     * `ZstdInputStream` call both inside and outside Robolectric on the same
     * JVM/JDK/library version: it works standalone, and fails with
     * `UnsupportedOperationException` only inside a `RobolectricTestRunner`
     * test, because Robolectric's Android-framework shadowing makes zstd-jni's
     * own "am I running on a real Android device" detection answer yes, and
     * then take a native-library-loading path that assumes the APK's own
     * install-time native library extraction already happened, which it
     * never does under Robolectric. DictionaryTest supplies a file the Gradle
     * build's own `extractTestDictionary` task decompresses ahead of time,
     * entirely outside any JVM the test itself runs in, sidestepping the
     * problem rather than fighting it.
     */
    private val preDecompressed: File? = null,
) {

    private var db: SQLiteDatabase? = null

    private suspend fun database(): SQLiteDatabase? = withContext(Dispatchers.IO) {
        db?.takeIf { it.isOpen }?.let { return@withContext it }
        runCatching {
            val file = extractIfNeeded()
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
                .also { db = it }
        }.getOrElse {
            Log.e(TAG, "could not open the dictionary", it)
            // Temporary, alongside the success-path report in extractIfNeeded:
            // a failure here was previously invisible except as "Not in the
            // dictionary" in the UI (defineTappedWord folds a null lookup and
            // a genuinely-missing word into the same notFound state), with no
            // way to tell them apart without this.
            CrashReporter.reportDiagnostic(
                context,
                TAG,
                "could not open the dictionary: ${it.javaClass.name}: ${it.message}\n${it.stackTraceToString()}",
            )
            null
        }
    }

    private fun extractIfNeeded(): File {
        preDecompressed?.let { return it }

        val target = File(context.filesDir, "dictionary-v$ASSET_VERSION.db")
        if (target.isFile && target.length() > 0) return target

        // A previous version's copy is dead weight once this one exists.
        context.filesDir.listFiles { f -> f.name.startsWith("dictionary-v") }
            ?.forEach { it.delete() }

        val elapsedMs = System.currentTimeMillis().let { start ->
            ZstdInputStream(context.assets.open(ASSET_NAME)).use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            System.currentTimeMillis() - start
        }
        Log.i(TAG, "decompressed dictionary asset in ${elapsedMs}ms (${target.length()} bytes)")
        CrashReporter.reportDiagnostic(
            context,
            TAG,
            "dictionary zstd decompression: ${elapsedMs}ms, ${target.length()} bytes extracted",
        )
        return target
    }

    /**
     * Look a word up, trying its inflected forms.
     *
     * @return the first form that has an entry, or null when nothing matches.
     */
    suspend fun lookup(rawWord: String): Definition? = withContext(Dispatchers.IO) {
        val database = database() ?: return@withContext null
        val queried = Lemmatizer.normalize(rawWord)
        if (queried.isEmpty()) return@withContext null

        val irregular = irregularBase(database, queried)
        for (candidate in Lemmatizer.candidates(queried, irregular)) {
            val senses = sensesFor(database, candidate)
            if (senses.isNotEmpty()) {
                return@withContext Definition(queried = queried, word = candidate, senses = senses)
            }
        }
        null
    }

    private fun irregularBase(database: SQLiteDatabase, word: String): String? =
        database.rawQuery("SELECT base FROM forms WHERE form = ? LIMIT 1", arrayOf(word)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    /**
     * A word with many parts of speech ("a", "he" — Wiktionary distinguishes
     * pronoun/determiner/noun/interjection/... far more finely than WordNet's
     * noun/verb/adjective/adverb ever did) would otherwise return every rank
     * of every part of speech, which is too many to usefully show in a small
     * popup. `ORDER BY rank, id` before the cap means this takes the *first*
     * sense of as many parts of speech as fit before going back for a
     * second sense of any one of them, rather than exhausting one part of
     * speech's senses before ever showing another's.
     */
    private fun sensesFor(database: SQLiteDatabase, word: String): List<Sense> =
        database.rawQuery(
            """
            SELECT senses.id, parts_of_speech.name, senses.gloss, senses.ipa
            FROM senses JOIN parts_of_speech ON parts_of_speech.id = senses.pos
            WHERE senses.word = ?
            ORDER BY senses.rank, senses.id
            LIMIT $MAX_SENSES_SHOWN
            """.trimIndent(),
            arrayOf(word),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Sense(
                            partOfSpeech = cursor.getString(1),
                            definition = cursor.getString(2),
                            synonyms = synonymsFor(database, cursor.getLong(0)),
                            pronunciation = cursor.getString(3),
                        ),
                    )
                }
            }
        }

    private fun synonymsFor(database: SQLiteDatabase, senseId: Long): List<String> =
        database.rawQuery("SELECT synonym FROM synonyms WHERE sense_id = ?", arrayOf(senseId.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    fun close() {
        db?.close()
        db = null
    }

    private companion object {
        const val TAG = "ReeddDictionary"
        const val ASSET_NAME = "dictionary.db.zst"

        /** Bump when the shipped database (or, as with the move to a
         *  zstd-compressed asset, how it's extracted) changes, to force a
         *  fresh copy rather than reusing whatever's already on disk. */
        const val ASSET_VERSION = 4

        /** See [sensesFor]'s own docstring. */
        const val MAX_SENSES_SHOWN = 8
    }
}
