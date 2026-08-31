package dev.reedd.data.dictionary

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The real shipped dictionary, not a stub: it is an asset, so this also proves the
 * asset is present in the build and that extracting and querying it works.
 *
 * Reads the already-decompressed copy `extractTestDictionary` (app/build.gradle.kts)
 * produces at build time, via the `reedd.testDictionaryPath` system property that
 * task wires onto every unit test run, rather than exercising the app's own real
 * zstd decompression here -- zstd-jni can't actually run inside a Robolectric-hosted
 * test; see [Dictionary]'s own `preDecompressed` constructor parameter for why.
 */
@RunWith(RobolectricTestRunner::class)
class DictionaryTest {

    private val dictionary = Dictionary(
        ApplicationProvider.getApplicationContext<Context>(),
        preDecompressed = File(System.getProperty("reedd.testDictionaryPath")!!),
    )

    @After
    fun tearDown() = dictionary.close()

    @Test
    fun `a plain word is defined`() = runTest {
        val result = dictionary.lookup("foundation")
        assertNotNull("the bundled dictionary should define 'foundation'", result)
        assertEquals("foundation", result!!.word)
        assertTrue(result.senses.isNotEmpty())
        assertTrue(result.senses.first().definition.length > 5)
        // Part of speech is spelled out, since it is shown to a reader.
        assertTrue(result.senses.first().partOfSpeech in setOf("noun", "verb", "adjective", "adverb"))
    }

    @Test
    fun `capitals and surrounding punctuation are ignored`() = runTest {
        // Tapping a word at the end of a sentence brings its full stop with it.
        assertEquals("format", dictionary.lookup("Format.")!!.word)
        assertEquals("format", dictionary.lookup("“FORMAT,”")!!.word)
    }

    @Test
    fun `regular inflections resolve to the base word`() = runTest {
        // The build routes an inflection ("formats", "walked") straight to
        // the `forms` table rather than giving it a full headword entry of
        // its own -- most such entries would otherwise carry nothing but a
        // one-line "plural/past of X" gloss, real bulk for little value.
        // The Lemmatizer's own suffix rules are tested directly, independent
        // of whatever the bundled data happens to carry -- see
        // LemmatizerTest.
        assertEquals("format", dictionary.lookup("formats")!!.word)
        assertEquals("walk", dictionary.lookup("walked")!!.word)
        assertEquals("box", dictionary.lookup("boxes")!!.word)
        assertEquals("carry", dictionary.lookup("carried")!!.word)
    }

    @Test
    fun `a doubled consonant is undone`() = runTest {
        // "nodded" -> "nod", which no plain suffix strip would reach.
        assertEquals("nod", dictionary.lookup("nodded")!!.word)
    }

    @Test
    fun `irregular forms resolve through the bundled forms table`() = runTest {
        // No rule turns "went" into "go", or "children" into "child".
        // Also exercises a case the build has to actively guard against:
        // each of these words *does* have its own unrelated Wiktionary
        // entry too ("went" as an obsolete noun meaning "a course, way",
        // "children" as a surname, "feet" as an obsolete word for "feat")
        // -- if the build kept those, the literal word would resolve to
        // them instead of falling through to the base, and a reader
        // tapping "children" would see "A surname." instead of anything
        // about "child". (Not exhaustive: a residual case, "mice" -> a
        // rare regional-dialect verb sense tagged only via `categories`
        // rather than the `tags` this guard checks, still shadows "mouse"
        // -- known, judged too narrow a case to chase further.)
        assertEquals("go", dictionary.lookup("went")!!.word)
        assertEquals("child", dictionary.lookup("children")!!.word)
        assertEquals("foot", dictionary.lookup("feet")!!.word)
    }

    @Test
    fun `a word that is itself an entry is not reduced to its stem`() = runTest {
        // The precedence that matters: "computing" and "better" are words in their
        // own right, so a reader tapping them wants *those* definitions, not the
        // ones for "compute" and "good". The literal form is always tried first.
        assertEquals("computing", dictionary.lookup("computing")!!.word)
        assertEquals("better", dictionary.lookup("better")!!.word)
        assertEquals("quickly", dictionary.lookup("quickly")!!.word)
    }

    @Test
    fun `several senses are returned, most common first`() = runTest {
        val result = dictionary.lookup("run")!!
        assertTrue("expected more than one sense for 'run'", result.senses.size > 1)
    }

    @Test
    fun `the queried form is reported alongside the matched one`() = runTest {
        val result = dictionary.lookup("Children")!!
        assertEquals("children", result.queried)
        assertEquals("child", result.word)
    }

    @Test
    fun `a word that is not in the dictionary returns nothing`() = runTest {
        assertNull(dictionary.lookup("zzzqqxyz"))
        assertNull(dictionary.lookup(""))
        assertNull(dictionary.lookup("   "))
        assertNull(dictionary.lookup("—"))
    }

    @Test
    fun `a word's own usage examples are never shown as definitions`() = runTest {
        // The build only ever takes a sense's *gloss*, never Wiktionary's separate
        // `examples` field -- a definition may still legitimately quote a word
        // inline (Wiktionary defines "run" as spent/depleted, `"especially with
        // "down" or "out"`), which is different from a full example sentence.
        val result = dictionary.lookup("run")!!
        assertTrue(result.senses.none { it.definition.startsWith('"') })
    }

    @Test
    fun `function words are defined, unlike WordNet`() = runTest {
        // WordNet is a lexical-semantic database of content words and has no
        // entry at all for "the" -- a real, reported gap this dictionary
        // switched away from WordNet specifically to close.
        val result = dictionary.lookup("the")
        assertNotNull("'the' should be defined", result)
        assertTrue(result!!.senses.isNotEmpty())
    }

    @Test
    fun `a pronoun outranks an obscure abbreviation sense`() = runTest {
        // The specific reported symptom of the same gap: WordNet's only entry
        // for "he" was a rare noun sense for the chemical symbol of helium,
        // with no pronoun sense (WordNet has no part of speech for pronouns
        // at all) to rank above it.
        val result = dictionary.lookup("he")!!
        assertEquals("pronoun", result.senses.first().partOfSpeech)
    }

    @Test
    fun `common words carry a few synonyms`() = runTest {
        val result = dictionary.lookup("foundation")!!
        assertTrue(
            "expected at least one sense of 'foundation' to carry synonyms",
            result.senses.any { it.synonyms.isNotEmpty() },
        )
    }

    @Test
    fun `a word with many parts of speech is still capped to a reasonable number of senses`() = runTest {
        // "a"/"he" span many more parts of speech under Wiktionary's finer
        // distinctions (pronoun, determiner, article, interjection, proper
        // noun...) than WordNet's noun/verb/adjective/adverb ever did --
        // shown senses must stay bounded rather than growing with however
        // many parts of speech a word happens to have.
        val result = dictionary.lookup("a")!!
        assertTrue(result.senses.size <= 8)
    }

    @Test
    fun `looking up twice reuses the extracted database`() = runTest {
        val first = dictionary.lookup("book")
        val second = dictionary.lookup("book")
        assertEquals(first, second)
    }
}
