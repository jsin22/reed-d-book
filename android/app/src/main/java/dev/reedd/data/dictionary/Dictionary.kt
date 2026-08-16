package dev.reedd.data.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One sense of a word. */
data class Sense(val partOfSpeech: String, val definition: String)

/** What a lookup found. [word] is the form actually matched, which may be a lemma. */
data class Definition(
    val queried: String,
    val word: String,
    val senses: List<Sense>,
)

/**
 * The bundled offline dictionary: WordNet 3.0, shipped in the APK.
 *
 * Offline by requirement — looking a word up while reading should not depend on a
 * network, and this app is used with a server that may be on the other side of the
 * house or not running at all.
 *
 * The database is an asset, so it has to be copied out before SQLite can open it
 * (assets live compressed inside the APK and have no file path). The copy is done
 * once and keyed by [ASSET_VERSION], so shipping a new database replaces the old one
 * rather than silently keeping it.
 *
 * Storage is normalised — words point at shared synsets — because a definition is
 * shared by every word in its synset and duplicating the text per word roughly
 * trebled the file.
 */
class Dictionary(private val context: Context) {

    private var db: SQLiteDatabase? = null

    private suspend fun database(): SQLiteDatabase? = withContext(Dispatchers.IO) {
        db?.takeIf { it.isOpen }?.let { return@withContext it }
        runCatching {
            val file = extractIfNeeded()
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
                .also { db = it }
        }.getOrElse {
            Log.e(TAG, "could not open the dictionary", it)
            null
        }
    }

    private fun extractIfNeeded(): File {
        val target = File(context.filesDir, "dictionary-v$ASSET_VERSION.db")
        if (target.isFile && target.length() > 0) return target

        // A previous version's copy is dead weight once this one exists.
        context.filesDir.listFiles { f -> f.name.startsWith("dictionary-v") }
            ?.forEach { it.delete() }

        context.assets.open(ASSET_NAME).use { input ->
            target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
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

    private fun sensesFor(database: SQLiteDatabase, word: String): List<Sense> =
        database.rawQuery(
            """
            SELECT synsets.pos, synsets.gloss
            FROM senses JOIN synsets ON synsets.id = senses.synset
            WHERE senses.word = ?
            ORDER BY senses.rank
            """.trimIndent(),
            arrayOf(word),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Sense(partOfSpeech = cursor.getString(0), definition = cursor.getString(1)))
                }
            }
        }

    fun close() {
        db?.close()
        db = null
    }

    private companion object {
        const val TAG = "ReeddDictionary"
        const val ASSET_NAME = "dictionary.db"

        /** Bump when the shipped database changes, to force a fresh copy. */
        const val ASSET_VERSION = 1
    }
}
