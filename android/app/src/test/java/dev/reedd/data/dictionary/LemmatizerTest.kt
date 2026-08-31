package dev.reedd.data.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests of the candidate-generation rules, independent of the bundled
 * dictionary's own data. Wiktionary turned out to carry its own "form of X"
 * entry for most common inflections ("nodded", "formats", "went" are all
 * real headwords in their own right now, unlike under WordNet) — see
 * DictionaryTest for the resulting real-lookup behavior — so these rules are
 * no longer exercised by every inflected word looked up through the live
 * database. They still matter as the fallback for whatever the bundled
 * dictionary doesn't carry its own entry for, so they're worth testing
 * directly rather than only indirectly through data that might stop
 * exercising them.
 */
class LemmatizerTest {

    @Test
    fun `a doubled consonant before -ing or -ed is undone`() {
        assertTrue("nod" in Lemmatizer.candidates("nodded"))
        assertTrue("run" in Lemmatizer.candidates("running"))
    }

    @Test
    fun `regular suffix rules produce the expected stem`() {
        assertTrue("format" in Lemmatizer.candidates("formats"))
        assertTrue("walk" in Lemmatizer.candidates("walked"))
        assertTrue("box" in Lemmatizer.candidates("boxes"))
        assertTrue("carry" in Lemmatizer.candidates("carried"))
    }

    @Test
    fun `the literal word is always the first candidate`() {
        assertEquals("running", Lemmatizer.candidates("running").first())
    }

    @Test
    fun `an irregular base is placed right after the literal form`() {
        val candidates = Lemmatizer.candidates("went", irregular = "go")
        assertEquals(listOf("went", "go"), candidates.take(2))
    }

    @Test
    fun `a possessive is treated as its own base word`() {
        assertTrue("dog" in Lemmatizer.candidates("dog's"))
    }
}
