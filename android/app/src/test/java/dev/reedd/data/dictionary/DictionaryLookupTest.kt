package dev.reedd.data.dictionary

import dev.reedd.data.remote.ApiException
import dev.reedd.data.remote.DefinitionDto
import dev.reedd.data.remote.SenseDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DictionaryLookupTest {

    private val walk = Definition("walk", "walk", listOf(Sense("verb", "To move on foot.")))

    private fun serverDefinition(queried: String, word: String) =
        DefinitionDto(queried, word, listOf(SenseDto("noun", "The quality of being universal.", "/ipa/", listOf("universality"))))

    @Test
    fun `a word in the bundled dictionary never asks the server`() = runTest {
        var asked = false
        val lookup = DictionaryLookup(local = { walk }, remote = { _, _ -> asked = true; error("unused") })
        assertEquals(LookupResult.Found(walk), lookup.lookup("walked"))
        assertTrue(!asked)
    }

    @Test
    fun `a miss asks the server with the word and its suffix-rule candidates`() = runTest {
        var sent: Pair<String, List<String>>? = null
        val lookup = DictionaryLookup(
            local = { null },
            remote = { word, candidates -> sent = word to candidates; serverDefinition(word, "catholicity") },
        )
        val result = lookup.lookup("“Catholicities,”")

        assertEquals("catholicities", sent!!.first)
        assertTrue("expected the -ies rule, got ${sent!!.second}", "catholicity" in sent!!.second)
        val found = (result as LookupResult.Found).definition
        assertEquals("catholicity", found.word)
        assertEquals(listOf("universality"), found.senses.single().synonyms)
        assertEquals("/ipa/", found.pronunciation)
    }

    @Test
    fun `a 404 from the server means the word really is not known`() = runTest {
        val lookup = DictionaryLookup(local = { null }, remote = { _, _ -> throw ApiException(404, "no definition") })
        assertEquals(LookupResult.NotFound, lookup.lookup("zzxyq"))
    }

    @Test
    fun `no network or a server error is unreachable, not not-found`() = runTest {
        val offline = DictionaryLookup(local = { null }, remote = { _, _ -> throw IOException("Unable to resolve host") })
        val broken = DictionaryLookup(local = { null }, remote = { _, _ -> throw ApiException(503, "not installed") })
        assertEquals(LookupResult.Unreachable, offline.lookup("quotidian"))
        assertEquals(LookupResult.Unreachable, broken.lookup("quotidian"))
    }

    @Test
    fun `a server that does not answer in time is unreachable`() = runTest {
        val slow = DictionaryLookup(local = { null }, remote = { word, _ -> delay(60_000); serverDefinition(word, word) })
        assertEquals(LookupResult.Unreachable, slow.lookup("quotidian"))
    }

    @Test
    fun `punctuation alone is not looked up anywhere`() = runTest {
        var asked = false
        val lookup = DictionaryLookup(local = { null }, remote = { _, _ -> asked = true; error("unused") })
        assertEquals(LookupResult.NotFound, lookup.lookup("…"))
        assertTrue(!asked)
    }
}
