package dev.reedd.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteLocatorsTest {

    @Test
    fun `context is taken from both sides of the word`() {
        val (before, after) = NoteLocators.surroundingContext("the quick brown fox jumps", offset = 4, word = "quick")
        assertEquals("the ", before)
        assertEquals(" brown fox jumps", after)
    }

    @Test
    fun `a word at the very start of the block has no before context`() {
        val (before, after) = NoteLocators.surroundingContext("quick brown fox", offset = 0, word = "quick")
        assertEquals("", before)
        assertEquals(" brown fox", after)
    }

    @Test
    fun `a word ending at the very end of the block has no after context`() {
        val text = "the quick brown fox"
        val (before, after) = NoteLocators.surroundingContext(text, offset = text.length - "fox".length, word = "fox")
        assertEquals("the quick brown ", before)
        assertEquals("", after)
    }

    @Test
    fun `context is clamped rather than reaching past the block`() {
        val text = "hi"
        val (before, after) = NoteLocators.surroundingContext(text, offset = 0, word = "hi")
        assertEquals("", before)
        assertEquals("", after)
    }
}
