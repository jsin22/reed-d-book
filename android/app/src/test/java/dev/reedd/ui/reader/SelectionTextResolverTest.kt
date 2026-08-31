package dev.reedd.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [org.json.JSONObject] is an unmocked Android stub in a plain JVM unit
 *  test -- Robolectric supplies a real implementation. */
@RunWith(RobolectricTestRunner::class)
class SelectionTextResolverTest {

    @Test
    fun `a well-formed result parses`() {
        val raw = """
            {"text":"quick brown fox","before":"the ","after":" jumps",
             "startX":10.0,"startY":20.0,"startBottom":30.0,
             "endX":100.0,"endY":20.0,"endBottom":30.0}
        """.trimIndent()

        val result = SelectionTextResolver.parse(raw)

        assertEquals("quick brown fox", result?.text)
        assertEquals("the ", result?.before)
        assertEquals(" jumps", result?.after)
        assertEquals(10.0f, result?.startX)
        assertEquals(100.0f, result?.endX)
    }

    @Test
    fun `evaluateJavascript's own JSON-encoded-string wrapping is unwrapped`() {
        // evaluateJavascript returns the script's own return value re-encoded
        // as JSON -- a returned string arrives quoted and escaped, exactly
        // like TapTextResolver.parse already has to handle.
        val raw = """"{\"text\":\"a word\",\"before\":\"\",\"after\":\"\",\"startX\":0,\"startY\":0,\"startBottom\":0,\"endX\":0,\"endY\":0,\"endBottom\":0}""""

        assertEquals("a word", SelectionTextResolver.parse(raw)?.text)
    }

    @Test
    fun `a JS-side null result parses to null`() {
        assertNull(SelectionTextResolver.parse("null"))
    }

    @Test
    fun `blank input parses to null`() {
        assertNull(SelectionTextResolver.parse(""))
        assertNull(SelectionTextResolver.parse("  "))
        assertNull(SelectionTextResolver.parse("\"\""))
    }

    @Test
    fun `malformed JSON parses to null rather than throwing`() {
        assertNull(SelectionTextResolver.parse("{not json"))
    }

    @Test
    fun `an empty selected text parses to null`() {
        // A drag that resolves to two identical points collapses to no text
        // at all -- nothing worth turning into a selection.
        val raw = """{"text":"","before":"","after":"","startX":0,"startY":0,"startBottom":0,"endX":0,"endY":0,"endBottom":0}"""
        assertNull(SelectionTextResolver.parse(raw))
    }

    @Test
    fun `missing fields fall back to sensible defaults rather than throwing`() {
        val raw = """{"text":"a word"}"""

        val result = SelectionTextResolver.parse(raw)

        assertEquals("a word", result?.text)
        assertEquals("", result?.before)
        assertEquals("", result?.after)
        assertEquals(0f, result?.startX)
    }
}
