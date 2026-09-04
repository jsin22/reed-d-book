package dev.reedd.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BreadcrumbsTest {

    @After
    fun tearDown() {
        Breadcrumbs.clear()
    }

    @Test
    fun `an empty trail says so rather than an empty string`() {
        assertEquals("(no breadcrumbs recorded)", Breadcrumbs.snapshot())
    }

    @Test
    fun `left messages appear in the snapshot, oldest first`() {
        Breadcrumbs.leave("reader opened")
        Breadcrumbs.leave("scroll mode toggled")
        Breadcrumbs.leave("font size changed")

        val lines = Breadcrumbs.snapshot().lines()

        assertEquals(3, lines.size)
        assertTrue(lines[0].endsWith("reader opened"))
        assertTrue(lines[1].endsWith("scroll mode toggled"))
        assertTrue(lines[2].endsWith("font size changed"))
    }

    @Test
    fun `the trail is capped, dropping the oldest entries first`() {
        repeat(50) { Breadcrumbs.leave("event $it") }

        val lines = Breadcrumbs.snapshot().lines()

        assertEquals(40, lines.size)
        assertTrue("oldest entries must be the ones dropped", lines[0].endsWith("event 10"))
        assertTrue(lines.last().endsWith("event 49"))
    }

    @Test
    fun `clear empties the trail`() {
        Breadcrumbs.leave("something happened")
        Breadcrumbs.clear()
        assertEquals("(no breadcrumbs recorded)", Breadcrumbs.snapshot())
    }
}
