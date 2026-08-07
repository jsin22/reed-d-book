package dev.reedd.data.remote

import dev.reedd.Fixtures
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deserialisation against manifests emitted by the real `server/app/store.py`.
 */
class JobDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private fun job(name: String) = json.decodeFromString<JobDto>(Fixtures.read(name))

    @Test
    fun `a queued job parses with every optional field absent`() {
        val job = job("job_queued.json")
        assertEquals("queued", job.status)
        assertEquals(JobStatus.QUEUED, JobStatus.fromWire(job.status))
        // The server sanitises the upload name; the app must not assume it kept
        // the one the user picked.
        assertEquals("A_Brief_Guide_to_Digital_Formats.epub", job.filename)
        assertEquals("af_heart", job.voice)
        assertEquals(1.0, job.speed, 0.0)
        assertEquals(0, job.progress)
        assertNull(job.eta)
        assertNull(job.startedAt)
        assertNull(job.audiobook)
        assertNull(job.sync)
        assertNull(job.error)
    }

    @Test
    fun `a running job carries progress and the server-formatted eta`() {
        val job = job("job_running.json")
        assertEquals(JobStatus.RUNNING, JobStatus.fromWire(job.status))
        assertEquals(32, job.progress)
        // Deliberately a string, not a duration: it is displayed verbatim.
        assertEquals("00d 00h 00m 11s", job.eta)
        assertEquals(1.25, job.speed, 0.0)
        assertEquals("9f2b1d33-0000-4444-8888-aaaabbbbcccc", job.celeryTaskId)
    }

    @Test
    fun `a done job names both deliverables with their sizes`() {
        val job = job("job_done.json")
        assertEquals(JobStatus.DONE, JobStatus.fromWire(job.status))
        assertTrue(JobStatus.fromWire(job.status).isTerminal)
        assertEquals(100, job.progress)
        assertEquals("A_Brief_Guide_to_Digital_Formats.m4b", job.audiobook?.file)
        // Bytes must be a Long: an audiobook can exceed Int.MAX_VALUE.
        assertEquals(543210987L, job.audiobook?.bytes)
        assertEquals("A_Brief_Guide_to_Digital_Formats.json", job.sync?.file)
        assertEquals(2481L, job.sync?.bytes)
    }

    @Test
    fun `an errored job carries the traceback tail`() {
        val job = job("job_error.json")
        assertEquals(JobStatus.ERROR, JobStatus.fromWire(job.status))
        assertTrue(JobStatus.fromWire(job.status).isTerminal)
        assertEquals("ffmpeg not found on PATH; audiblez produced no .m4b", job.error)
        // Progress is whatever it reached; it is not reset on failure.
        assertEquals(17, job.progress)
    }

    @Test
    fun `unknown fields are ignored, so a newer server does not break this build`() {
        val withExtra = Fixtures.read("job_queued.json")
            .trimEnd().removeSuffix("}") + ""","queue_position": 3, "gpu": "rocm"}"""
        val job = json.decodeFromString<JobDto>(withExtra)
        assertEquals(JobStatus.QUEUED, JobStatus.fromWire(job.status))
    }

    @Test
    fun `an unrecognised status is UNKNOWN rather than an exception`() {
        assertEquals(JobStatus.UNKNOWN, JobStatus.fromWire("paused"))
        assertEquals(JobStatus.UNKNOWN, JobStatus.fromWire(null))
        // and UNKNOWN must not look finished, or the app would try to download
        assertTrue(!JobStatus.UNKNOWN.isTerminal)
    }

    @Test
    fun `status matching is case insensitive`() {
        assertEquals(JobStatus.DONE, JobStatus.fromWire("DONE"))
    }

    @Test
    fun `the voices list parses, including the empty case`() {
        assertEquals(
            listOf("af_heart", "af_sky"),
            json.decodeFromString<VoicesDto>("""{"voices":["af_heart","af_sky"],"default":"af_heart"}""").voices,
        )
        // The server returns [] when audiblez is not importable, and then skips
        // validation -- the app must show a free-text field rather than an empty picker.
        val none = json.decodeFromString<VoicesDto>("""{"voices":[],"default":"af_heart"}""")
        assertTrue(none.voices.isEmpty())
        assertEquals("af_heart", none.default)
    }
}
