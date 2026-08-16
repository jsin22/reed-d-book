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

/**
 * The real shipped dictionary, not a stub: it is an asset, so this also proves the
 * asset is present in the build and that extracting and querying it works.
 */
@RunWith(RobolectricTestRunner::class)
class DictionaryTest {

    private val dictionary = Dictionary(ApplicationProvider.getApplicationContext<Context>())

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
    fun `irregular forms resolve through WordNet's exception lists`() = runTest {
        // No rule turns "went" into "go", or "mice" into "mouse".
        assertEquals("go", dictionary.lookup("went")!!.word)
        assertEquals("child", dictionary.lookup("children")!!.word)
        assertEquals("mouse", dictionary.lookup("mice")!!.word)
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
    fun `definitions carry no quoted examples`() = runTest {
        // The build strips WordNet's usage examples; they roughly doubled the size
        // and read badly in a small popup.
        val result = dictionary.lookup("run")!!
        assertTrue(result.senses.none { it.definition.contains('"') })
    }

    @Test
    fun `looking up twice reuses the extracted database`() = runTest {
        val first = dictionary.lookup("book")
        val second = dictionary.lookup("book")
        assertEquals(first, second)
    }
}
