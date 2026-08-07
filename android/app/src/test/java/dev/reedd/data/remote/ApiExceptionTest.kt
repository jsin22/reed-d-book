package dev.reedd.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiExceptionTest {

    @Test
    fun `a FastAPI detail string is unwrapped`() {
        assertEquals(
            "no such job: abc",
            ApiException.parseDetail("""{"detail":"no such job: abc"}"""),
        )
    }

    @Test
    fun `a validation error detail list is flattened to its messages`() {
        // FastAPI returns this shape for a 422, e.g. a speed outside the range.
        val body = """
            {"detail":[
              {"loc":["body","speed"],"msg":"value is not a valid float","type":"type_error.float"},
              {"loc":["body","file"],"msg":"field required","type":"value_error.missing"}
            ]}
        """.trimIndent()
        assertEquals(
            "value is not a valid float; field required",
            ApiException.parseDetail(body),
        )
    }

    @Test
    fun `a body that is not the expected shape falls back to the raw text`() {
        assertEquals("<html>502 Bad Gateway</html>", ApiException.parseDetail("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun `an empty body is null`() {
        assertNull(ApiException.parseDetail(null))
        assertNull(ApiException.parseDetail(""))
        assertNull(ApiException.parseDetail("   "))
    }

    @Test
    fun `status codes classify the way the callers branch on them`() {
        assertTrue(ApiException(404, "gone").isNotFound)
        assertTrue(ApiException(401, "nope").isUnauthorized)
        assertTrue(ApiException(409, "job is running").isNotReady)
        assertTrue(ApiException(410, "audiobook is no longer on disk").isGone)
        assertFalse(ApiException(500, "boom").isNotFound)
    }

    @Test
    fun `the message carries code, detail and url for the log`() {
        val e = ApiException(409, "job is running", "http://host:8000/api/jobs/x/audiobook")
        assertEquals(
            "HTTP 409: job is running (http://host:8000/api/jobs/x/audiobook)",
            e.message,
        )
    }
}
