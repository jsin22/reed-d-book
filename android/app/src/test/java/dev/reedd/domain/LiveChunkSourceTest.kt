package dev.reedd.domain

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.reedd.Fixtures
import dev.reedd.data.local.BookFiles
import dev.reedd.data.remote.ApiProvider
import dev.reedd.playback.PlayerConnection
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.nio.file.Files as NioFiles

/**
 * The real end-to-end session flow -- MockWebServer standing in for
 * app.live_reading, a real epub (the same `sample-short.epub` fixture
 * ChunkAlignerTest uses) so alignment runs for real, not mocked. Only
 * [PlayerConnection] stays inert: it is never `connect()`ed, so its own
 * methods (used here) silently no-op rather than needing a real
 * MediaController -- this test is about LiveChunkSource's own bookkeeping
 * (chunk arrival, alignment, cumulative timing, session lifecycle), not
 * Media3 playback, which cannot be exercised without a real device anyway.
 */
@RunWith(RobolectricTestRunner::class)
class LiveChunkSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var player: PlayerConnection
    private lateinit var files: BookFiles
    private val resourceHref = "EPUB/understanding_digital_formats.xhtml"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        player = PlayerConnection(context)
        files = BookFiles(context)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun api() = ApiProvider(baseUrl = { server.url("/").toString() }, token = { "test-token" })

    private fun enqueueJson(body: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build())
    }

    /** Stand-in "audio" -- LiveChunkSource only ever writes these bytes to a
     *  cache file and hands the file to PlayerConnection, which (never
     *  connect()ed here) no-ops before reading them, so real WAV content is
     *  not needed for this test. */
    private fun enqueueAudio(content: String) {
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "audio/wav").body(content).build())
    }

    private fun epubPath(): java.io.File {
        val path = NioFiles.createTempFile("sample", ".epub").toFile()
        path.writeBytes(Fixtures.readBytes("sample-short.epub"))
        path.deleteOnExit()
        return path
    }

    /**
     * Robolectric's main looper is paused by default -- LiveChunkSource hops
     * to it (`withContext(Dispatchers.Main)`) for the one call that actually
     * touches [PlayerConnection]'s MediaController, matching that class's own
     * documented main-thread requirement. Nothing pumps that looper's queue
     * on its own, so this idles it every iteration; without that the
     * dispatched work sits queued forever and this always times out.
     */
    private fun await(timeoutMs: Long = 5_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(20)
        }
        throw AssertionError("condition not met in time")
    }

    @Test
    fun `chunks arrive, align against the real epub, and the chapter finishes cleanly`() = runBlocking {
        enqueueJson("""{"session_id":"s1"}""")
        enqueueJson(
            """{"status":"running","error":null,"resource_href":"$resourceHref","cursor":0,"total_sentences":2,
                "chunks":[{"index":0,"duration_s":2.375,"chars":10,"text":"\n\nUnderstanding Digital Formats."}]}"""
        )
        enqueueAudio("chunk-0")
        enqueueJson(
            """{"status":"done","error":null,"resource_href":"$resourceHref","cursor":0,"total_sentences":2,
                "chunks":[{"index":0,"duration_s":2.375,"chars":10,"text":"\n\nUnderstanding Digital Formats."},
                          {"index":1,"duration_s":4.275,"chars":10,"text":"\nDigital file formats are the foundation of modern computing."}]}"""
        )
        enqueueAudio("chunk-1")
        enqueueJson("""{"stopped":true}""")

        val source = LiveChunkSource(
            bookId = "b1",
            jobId = "job-1",
            epub = epubPath(),
            voice = "alba",
            title = "A Brief Guide to Digital Formats",
            author = "Sample Generator",
            coverPath = null,
            api = api(),
            player = player,
            files = files,
            nextResourceHref = { null }, // a single-chapter test -- nothing after this one
        )

        source.startFrom(resourceHref, sentenceIndex = 0)

        await { source.chunkIndexFlow.value.size == 2 }
        val index = source.chunkIndexFlow.value
        assertEquals(0L, index.chunkAtIndex(0)!!.startMs)
        assertEquals(2_375L, index.chunkAtIndex(1)!!.startMs) // cumulative: chunk 0's own 2.375s duration
        assertEquals(6_650L, index.chunkAtIndex(1)!!.endMs) // 2375 + 4275
        assertTrue("expected the heading to align against the real epub text", index.chunkAtIndex(0)!!.isAligned)
        assertEquals(resourceHref, index.chunkAtIndex(0)!!.resourceHref)

        assertEquals("EPUB/understanding_digital_formats.xhtml" to 0, source.resumePointFor(0))
        assertEquals("EPUB/understanding_digital_formats.xhtml" to 1, source.resumePointFor(1))
        assertNull(source.resumePointFor(99))

        await { source.finished.value }
        assertNull(source.message.value)
    }

    /**
     * The happy-path test above delivers every chunk in a single `/status`
     * response. Real synthesis does not: chunks trickle in one at a time
     * across many polls (`POLL_INTERVAL_MS`). Each new batch is run through
     * `aligner.align(entities, ...)` **separately** -- `entities` is only
     * the newly-arrived chunks, not the whole chapter accumulated so far --
     * and `ChunkAligner.alignChapter`'s own search cursor starts at 0 on
     * every call. This reproduces that trickle to check whether later
     * chunks (aligned by a *different* `align()` call than the first) still
     * align at all, not just the first batch's own chunks.
     */
    @Test
    fun `chunks trickling in one at a time still all align`() = runBlocking {
        val sentences = listOf(
            "\n\nUnderstanding Digital Formats.",
            "\nDigital file formats are the foundation of modern computing.",
            " Every document, image, audio track, and video is stored in a specific format that determines how the data is organized and accessed.",
        )
        enqueueJson("""{"session_id":"s1"}""")
        for (i in sentences.indices) {
            val chunksJson = sentences.take(i + 1).mapIndexed { idx, text ->
                """{"index":$idx,"duration_s":2.0,"chars":10,"text":${jsonQuote(text)}}"""
            }.joinToString(",")
            val status = if (i == sentences.lastIndex) "done" else "running"
            enqueueJson(
                """{"status":"$status","error":null,"resource_href":"$resourceHref","cursor":0,
                    "total_sentences":${sentences.size},"chunks":[$chunksJson]}"""
            )
            enqueueAudio("chunk-$i")
        }
        enqueueJson("""{"stopped":true}""")

        val source = LiveChunkSource(
            bookId = "b1",
            jobId = "job-1",
            epub = epubPath(),
            voice = "alba",
            title = "A Brief Guide to Digital Formats",
            author = "Sample Generator",
            coverPath = null,
            api = api(),
            player = player,
            files = files,
            nextResourceHref = { null },
        )

        source.startFrom(resourceHref, sentenceIndex = 0)

        await { source.chunkIndexFlow.value.size == sentences.size }
        val index = source.chunkIndexFlow.value
        for (i in sentences.indices) {
            assertTrue("chunk $i (from its own, later align() call) should still align",
                index.chunkAtIndex(i)!!.isAligned)
        }
    }

    /**
     * The user's own real, live-tested complaint: live reading skipping
     * sentences. Root cause: the server prunes already-consumed chunks off
     * the *front* of its own `status().chunks` list as `advance()` reports
     * progress (bounds its memory) -- that list is not append-only, it can
     * shrink. `runChapter` used to track "seen" as a plain count
     * (`status.chunks.drop(seenCount)`); once pruning shrinks the list
     * below that count, `drop()` returns nothing and any genuinely new
     * chunk in that same response is silently missed -- never downloaded,
     * never queued, never played. Reproduced here: the first poll returns
     * chunks 0-2, the second (simulating pruning once the reader has moved
     * past them) returns only chunks 2-3 -- chunk 3 is new and must not be
     * dropped just because the list is now shorter than before.
     */
    @Test
    fun `a new chunk is not skipped when the server prunes already-consumed ones from the same response`() = runBlocking {
        enqueueJson("""{"session_id":"s1"}""")
        enqueueJson(
            """{"status":"running","error":null,"resource_href":"$resourceHref","cursor":0,"total_sentences":4,
                "chunks":[{"index":0,"duration_s":1.0,"chars":5,"text":"a"},
                          {"index":1,"duration_s":1.0,"chars":5,"text":"b"},
                          {"index":2,"duration_s":1.0,"chars":5,"text":"c"}]}"""
        )
        enqueueAudio("chunk-0")
        enqueueAudio("chunk-1")
        enqueueAudio("chunk-2")
        // Chunks 0 and 1 pruned server-side (the reader has already moved
        // past them); chunk 3 is genuinely new and must still get through.
        enqueueJson(
            """{"status":"done","error":null,"resource_href":"$resourceHref","cursor":2,"total_sentences":4,
                "chunks":[{"index":2,"duration_s":1.0,"chars":5,"text":"c"},
                          {"index":3,"duration_s":1.0,"chars":5,"text":"d"}]}"""
        )
        enqueueAudio("chunk-3")
        enqueueJson("""{"stopped":true}""")

        val source = LiveChunkSource(
            bookId = "b1", jobId = "job-1", epub = epubPath(), voice = "alba",
            title = "A Brief Guide to Digital Formats", author = "Sample Generator", coverPath = null,
            api = api(), player = player, files = files, nextResourceHref = { null },
        )

        source.startFrom(resourceHref, sentenceIndex = 0)

        await { source.finished.value }
        // If chunk 3 had been skipped, its own /live/chunk/3 request would
        // never have been sent -- server.takeRequest() below would time out
        // or return a stale request instead of that one.
        val paths = generateSequence { server.takeRequest(0, java.util.concurrent.TimeUnit.MILLISECONDS) }
            .map { it.url.encodedPath }.toList()
        assertTrue("expected a request for chunk 3's audio, got: $paths", paths.any { it.endsWith("/chunk/3") })
    }

    /**
     * "Read from here" on a word with nothing aligned there yet -- the
     * common case for a live book, since most of it has simply never been
     * visited. [LiveChunkSource.startFromFraction] takes the tap's own
     * resource + progression directly (no book-wide resolution needed --
     * the seek bar's own, separate concern is `ReaderViewModel.
     * goToProgression`, which never touches this class at all) and lets
     * the server turn the proportion into a real sentence index -- checked
     * here via the actual outgoing request body, since the whole point of
     * sending a fraction rather than a guessed index is what ends up on
     * the wire.
     */
    @Test
    fun `startFromFraction sends a fraction and uses the server's resolved index`() = runBlocking {
        enqueueJson("""{"session_id":"s1","from_sentence_index":1}""")
        enqueueJson(
            """{"status":"done","error":null,"resource_href":"$resourceHref","cursor":1,
                "total_sentences":2,"chunks":[{"index":1,"duration_s":4.275,"chars":10,
                "text":"\nDigital file formats are the foundation of modern computing."}]}"""
        )
        enqueueAudio("chunk-1")
        enqueueJson("""{"stopped":true}""")

        val source = LiveChunkSource(
            bookId = "b1",
            jobId = "job-1",
            epub = epubPath(),
            voice = "alba",
            title = "A Brief Guide to Digital Formats",
            author = "Sample Generator",
            coverPath = null,
            api = api(),
            player = player,
            files = files,
            nextResourceHref = { null },
        )

        source.startFromFraction(resourceHref, 0.5)

        val request = server.takeRequest()
        assertEquals("/api/books/job-1/live/start", request.url.encodedPath)
        val body = request.body!!.utf8()
        assertTrue("expected from_fraction in the request body: $body", body.contains("\"from_fraction\":0.5"))

        await { source.chunkIndexFlow.value.size == 1 }
        // The server resolved fraction 0.5 to real sentence index 1 (of 2) --
        // resumePointFor has to reflect that, not the fraction that was sent.
        assertEquals(resourceHref to 1, source.resumePointFor(0))
    }

    /**
     * The reader's own request: picking a new voice mid-session should not
     * require closing and reopening the book -- it should "resend the
     * current sentence" (this test's own [resourceHref]/sentence index)
     * right away, in the new voice.
     */
    @Test
    fun `applyVoice restarts at the given spot using the new voice`() = runBlocking {
        // First "chapter": one sentence, done immediately -- finishes and
        // self-releases on its own, same as the happy-path test's shape.
        enqueueJson("""{"session_id":"s1"}""")
        enqueueJson(
            """{"status":"done","error":null,"resource_href":"$resourceHref","cursor":0,"total_sentences":1,
                "chunks":[{"index":0,"duration_s":2.375,"chars":10,"text":"\n\nUnderstanding Digital Formats."}]}"""
        )
        enqueueAudio("chunk-0")
        enqueueJson("""{"stopped":true}""")
        // changeVoice's own restart, at sentence index 1, in the new voice.
        enqueueJson("""{"session_id":"s2"}""")
        enqueueJson(
            """{"status":"done","error":null,"resource_href":"$resourceHref","cursor":1,"total_sentences":2,
                "chunks":[{"index":1,"duration_s":4.275,"chars":10,"text":"\nDigital file formats are the foundation of modern computing."}]}"""
        )
        enqueueAudio("chunk-1")
        enqueueJson("""{"stopped":true}""")

        val source = LiveChunkSource(
            bookId = "b1", jobId = "job-1", epub = epubPath(), voice = "alba",
            title = "A Brief Guide to Digital Formats", author = "Sample Generator", coverPath = null,
            api = api(), player = player, files = files, nextResourceHref = { null },
        )

        source.startFrom(resourceHref, sentenceIndex = 0)
        await { source.finished.value }

        source.applyVoice("selene", resumeAt = resourceHref to 1)
        // A fresh session, ordinal 0 again -- applyVoice is a hard reset,
        // same as any other restart, not a seamless handoff.
        await { source.chunkIndexFlow.value.size == 1 && source.chunkIndexFlow.value.chunkAtIndex(0)?.startMs == 0L }

        val restartRequest = generateSequence { server.takeRequest(0, java.util.concurrent.TimeUnit.MILLISECONDS) }
            .first { it.url.encodedPath == "/api/books/job-1/live/start" && it.body!!.utf8().contains("selene") }
        val body = restartRequest.body!!.utf8()
        assertTrue("expected the new voice in the restart request: $body", body.contains("\"voice\":\"selene\""))
        assertTrue("expected the same sentence index: $body", body.contains("\"from_sentence_index\":1"))
    }

    /**
     * Reported live: a voice picked while the first sentence was still being
     * synthesized was dropped -- the change only ever applied once something
     * had played -- so the session kept going, and played, in the old voice.
     * Applying it now redoes that same pending start in the new voice.
     */
    @Test
    fun `applyVoice while the first sentence is still preparing restarts that start in the new voice`() = runBlocking {
        // Answered by path, not from a queue: how many status polls land before
        // the voice change depends on timing.
        val startBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                fun json(body: String) = MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body).build()
                return when {
                    path.endsWith("/live/start") -> {
                        startBodies += request.body!!.utf8()
                        json("""{"session_id":"s${startBodies.size}"}""")
                    }
                    // The first session never gets past preparing; the second delivers.
                    path.endsWith("/s1/status") -> json(
                        """{"status":"running","error":null,"resource_href":"$resourceHref","cursor":0,"total_sentences":1,"chunks":[]}"""
                    )
                    path.endsWith("/s2/status") -> json(
                        """{"status":"done","error":null,"resource_href":"$resourceHref","cursor":0,"total_sentences":1,
                            "chunks":[{"index":0,"duration_s":2.375,"chars":10,"text":"\n\nUnderstanding Digital Formats."}]}"""
                    )
                    path.contains("/chunk/") -> MockResponse.Builder().code(200).setHeader("Content-Type", "audio/wav").body("chunk").build()
                    else -> json("""{"stopped":true}""")
                }
            }
        }

        val source = LiveChunkSource(
            bookId = "b1", jobId = "job-1", epub = epubPath(), voice = "alba",
            title = "A Brief Guide to Digital Formats", author = "Sample Generator", coverPath = null,
            api = api(), player = player, files = files, nextResourceHref = { null },
        )

        source.startFrom(resourceHref, sentenceIndex = 0)
        await { startBodies.size == 1 && source.preparing.value }

        source.applyVoice("selene", resumeAt = null)
        await { source.chunkIndexFlow.value.size == 1 }

        assertEquals(2, startBodies.size)
        assertTrue("first start used the original voice: ${startBodies[0]}", startBodies[0].contains("\"voice\":\"alba\""))
        assertTrue("restart used the new voice: ${startBodies[1]}", startBodies[1].contains("\"voice\":\"selene\""))
        assertEquals("restart kept the same start", startBodies[0].replace("alba", "selene"), startBodies[1])
        await { !source.preparing.value }
    }

    /**
     * The user's own real, live-tested complaint: Readium's own `progression`
     * at tap time can drift meaningfully from a raw character-offset
     * position over a long resource -- confirmed live, a resource with
     * ~470 sentences, a couple percentage points of drift was 9+ sentences,
     * enough to visibly land on the wrong page. [LiveChunkSource.
     * startFromTap] should prefer the exact position it can compute from
     * the tap's own block text + offset over the (deliberately wrong here,
     * to prove it loses) Readium progression passed alongside it.
     */
    @Test
    fun `startFromTap prefers the exact block position over Readium's own progression`() = runBlocking {
        val blockText = "File formats can be broadly categorized into several types: document formats " +
            "like PDF and DOCX, image formats like PNG and JPG, audio formats like MP3 and WAV, " +
            "and video formats like MP4 and MKV."
        val resource = dev.reedd.data.align.EpubTextExtractor.extract(epubPath())
            .first { it.href.endsWith("understanding_digital_formats.xhtml") }
        val offsetWithinBlock = blockText.indexOf("PDF")
        val expectedFraction = (resource.text.indexOf(blockText) + offsetWithinBlock).toDouble() / resource.text.length

        enqueueJson("""{"session_id":"s1","from_sentence_index":1}""")
        enqueueJson(
            """{"status":"done","error":null,"resource_href":"$resourceHref","cursor":1,
                "total_sentences":4,"chunks":[{"index":1,"duration_s":2.0,"chars":10,"text":"x"}]}"""
        )
        enqueueAudio("chunk-1")
        enqueueJson("""{"stopped":true}""")

        val source = LiveChunkSource(
            bookId = "b1", jobId = "job-1", epub = epubPath(), voice = "alba",
            title = "A Brief Guide to Digital Formats", author = "Sample Generator", coverPath = null,
            api = api(), player = player, files = files, nextResourceHref = { null },
        )

        // 0.99 is a deliberately wrong Readium progression -- the fallback
        // this must NOT use, since the exact block text is findable.
        source.startFromTap(resourceHref, blockText, offsetWithinBlock, withinResourceFraction = 0.99)

        val body = server.takeRequest().body!!.utf8()
        assertTrue(
            "expected the precise fraction ($expectedFraction), not the Readium fallback (0.99), in: $body",
            body.contains("\"from_fraction\":$expectedFraction"),
        )
        assertTrue("expected an anchor_text window containing the tapped word, in: $body", body.contains("PDF"))
        assertTrue("expected an anchor_text field on the wire: $body", body.contains("\"anchor_text\":"))
    }

    private fun jsonQuote(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
