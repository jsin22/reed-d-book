package dev.reedd.domain

import dev.reedd.data.remote.GuideChapterDto
import dev.reedd.data.remote.GuideCharacterDto
import dev.reedd.data.remote.GuideDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookGuideViewTest {

    private fun chapter(index: Int, summary: String, vararg characters: GuideCharacterDto, status: String = "done") =
        GuideChapterDto(index, "6999_42-h-$index.htm.xhtml", "Chapter $index", status, summary, characters.toList())

    private val guide = GuideDto(chapters = listOf(
        chapter(0, ""),
        chapter(1, "Enfield tells of the door.", GuideCharacterDto("Mr. Hyde", listOf("my gentleman"), "A detestable man.")),
        chapter(2, "Utterson reads the will.", GuideCharacterDto("Dr. Jekyll", listOf("Henry Jekyll"), "A doctor.")),
        chapter(3, "SPOILER the reveal.",
            GuideCharacterDto("Henry Jekyll", listOf("Dr. Jekyll"), "SPOILER is Hyde."),
            GuideCharacterDto("Edward Hyde", listOf("Mr. Hyde"), "SPOILER is Jekyll.")),
    ))

    @Test
    fun `the chapter is found by resource filename, whatever the path`() {
        assertEquals(2, BookGuideView.chapterIndexOf(guide, "OEBPS/6999_42-h-2.htm.xhtml"))
        assertNull(BookGuideView.chapterIndexOf(guide, "OEBPS/nav.xhtml"))
        assertNull(BookGuideView.chapterIndexOf(guide, null))
    }

    @Test
    fun `story so far is only finished chapters, never the current one or later`() {
        val recaps = BookGuideView.storySoFar(guide, currentIndex = 2)
        assertEquals(listOf("Enfield tells of the door."), recaps.map { it.summary })
        assertTrue(BookGuideView.storySoFar(guide, 3).none { "SPOILER" in it.summary })
    }

    @Test
    fun `characters are as of the previous chapter`() {
        val inChapterTwo = BookGuideView.charactersSoFar(guide, currentIndex = 2)
        assertEquals(listOf("Mr. Hyde"), inChapterTwo.map { it.name })
        val inChapterThree = BookGuideView.charactersSoFar(guide, currentIndex = 3)
        assertEquals(listOf("Mr. Hyde", "Dr. Jekyll"), inChapterThree.map { it.name })
        assertTrue(inChapterThree.none { "SPOILER" in it.description })
    }

    @Test
    fun `a renamed character merges through an alias and keeps the first name`() {
        val atEnd = BookGuideView.charactersSoFar(guide, currentIndex = 4)
        assertEquals(listOf("Mr. Hyde", "Dr. Jekyll"), atEnd.map { it.name })
        val jekyll = atEnd[1]
        assertEquals("SPOILER is Hyde.", jekyll.description)
        assertEquals(listOf("Henry Jekyll"), jekyll.aliases)
        assertEquals(listOf("my gentleman", "Edward Hyde"), atEnd[0].aliases)
    }

    @Test
    fun `a chapter still being built is left out`() {
        val partial = guide.copy(chapters = guide.chapters.mapIndexed { i, c -> if (i == 1) c.copy(status = "pending") else c })
        assertTrue(BookGuideView.storySoFar(partial, 3).none { it.index == 1 })
        assertTrue(BookGuideView.charactersSoFar(partial, 3).none { it.name == "Mr. Hyde" })
        assertFalse(BookGuideView.isComplete(partial))
        assertTrue(BookGuideView.isComplete(guide))
    }

    // -- "Who is this?" ------------------------------------------------------------

    private val cast = listOf(
        GuideCharacterDto("Mr. Utterson", listOf("the lawyer", "Gabriel John Utterson"), "A lawyer."),
        GuideCharacterDto("Mr. Hyde", listOf("Edward Hyde"), "A cruel man."),
        GuideCharacterDto("Dr. Jekyll", listOf("Henry Jekyll", "Harry"), "A doctor."),
        GuideCharacterDto("Poole", emptyList(), "Jekyll's butler."),
    )

    @Test
    fun `a tapped surname finds the character, titles aside`() {
        assertEquals("Mr. Utterson", BookGuideView.findCharacter(cast, "Utterson")?.name)
        assertEquals("Mr. Hyde", BookGuideView.findCharacter(cast, "Hyde")?.name)
        assertEquals("Mr. Hyde", BookGuideView.findCharacter(cast, "Mr. Hyde")?.name)
    }

    @Test
    fun `punctuation and possessives around a tap are ignored`() {
        assertEquals("Mr. Hyde", BookGuideView.findCharacter(cast, "Hyde's")?.name)
        assertEquals("Mr. Hyde", BookGuideView.findCharacter(cast, "“Hyde,”")?.name)
        assertEquals("Dr. Jekyll", BookGuideView.findCharacter(cast, "Harry!")?.name)
    }

    @Test
    fun `an exact alias wins over a partial match`() {
        // Matched as a whole alias, not word by word.
        assertEquals("Dr. Jekyll", BookGuideView.findCharacter(cast, "Henry Jekyll")?.name)
    }

    @Test
    fun `an unknown name or a bare title finds nobody`() {
        assertNull(BookGuideView.findCharacter(cast, "Lanyon"))
        assertNull(BookGuideView.findCharacter(cast, "Mr."))
        assertNull(BookGuideView.findCharacter(cast, "..."))
    }

    @Test
    fun `only capitalized taps of up to four words look like names`() {
        assertTrue(BookGuideView.looksLikeName("Utterson"))
        assertTrue(BookGuideView.looksLikeName("“Hyde's"))
        assertTrue(BookGuideView.looksLikeName("Sir Danvers Carew"))
        assertFalse(BookGuideView.looksLikeName("lawyer"))
        assertFalse(BookGuideView.looksLikeName("..."))
        assertFalse(BookGuideView.looksLikeName("The man who walked down the street"))
    }
}

