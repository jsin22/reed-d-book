package dev.reedd.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {

    @Test
    fun `bare host and port gets an http scheme and a trailing slash`() {
        assertEquals("http://192.168.1.101:8000/", ServerAddress.normalize("192.168.1.101:8000"))
        assertEquals("http://pocket4.local:8000/", ServerAddress.normalize("pocket4.local:8000"))
    }

    @Test
    fun `an explicit scheme is kept`() {
        assertEquals("http://pocket4.local:8000/", ServerAddress.normalize("http://pocket4.local:8000"))
        assertEquals("https://pocket4.local/", ServerAddress.normalize("https://pocket4.local"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals("http://10.0.2.2:8000/", ServerAddress.normalize("  10.0.2.2:8000\n"))
    }

    @Test
    fun `a trailing slash is not doubled`() {
        assertEquals("http://host:8000/", ServerAddress.normalize("http://host:8000/"))
    }

    @Test
    fun `a path prefix survives, so a reverse proxy mount still works`() {
        // Retrofit resolves "api/jobs" against the base URL, so the base has to
        // keep its trailing slash or the last segment would be replaced.
        assertEquals("http://host/reedd/", ServerAddress.normalize("http://host/reedd"))
        assertEquals("http://host/reedd/", ServerAddress.normalize("http://host/reedd/"))
    }

    @Test
    fun `query and fragment are dropped`() {
        assertEquals("http://host:8000/", ServerAddress.normalize("http://host:8000/?a=b#c"))
    }

    @Test
    fun `unusable input is null rather than a crash`() {
        // Retrofit throws from baseUrl() on a malformed URL, which would turn a
        // typo in Settings into a crash.
        assertNull(ServerAddress.normalize(null))
        assertNull(ServerAddress.normalize(""))
        assertNull(ServerAddress.normalize("   "))
        assertNull(ServerAddress.normalize("http://"))
        assertNull(ServerAddress.normalize("not a url at all"))
    }

    @Test
    fun `isValid mirrors normalize`() {
        assertTrue(ServerAddress.isValid("10.0.2.2:8000"))
        assertFalse(ServerAddress.isValid("::::"))
    }
}
