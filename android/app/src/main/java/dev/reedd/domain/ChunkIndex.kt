package dev.reedd.domain

import dev.reedd.data.db.SyncChunkEntity

/**
 * The read-along mapping, in memory, searchable by playback position.
 *
 * Loaded once when a book is opened rather than queried per tick. The position is
 * checked several times a second while audio plays; a database round trip at that
 * rate would be indefensible when the whole mapping is a few megabytes even for a
 * novel (tens of thousands of sentences).
 *
 * Lookups are a binary search, so they cost nothing and — unlike walking forward
 * from the last known chunk — they are correct after an arbitrary seek, a scrub
 * backwards, or a jump to a chapter.
 *
 * @param offsetMs added to the player's position before searching. The `.m4b`
 *   carries a few tens of milliseconds of AAC priming that the timestamps do not
 *   describe (`audiblez/SYNC.md`), so this is where that is corrected.
 */
class ChunkIndex(
    chunks: List<SyncChunkEntity>,
    val offsetMs: Long = 0,
) {
    /** Playback order, which the query guarantees. */
    val chunks: List<SyncChunkEntity> = chunks

    private val starts: LongArray = LongArray(chunks.size) { chunks[it].startMs }

    val size: Int get() = starts.size
    val isEmpty: Boolean get() = starts.isEmpty()

    /** Total mapped length, i.e. the end of the last sentence. */
    val durationMs: Long get() = chunks.lastOrNull()?.endMs ?: 0

    /**
     * Index of the sentence covering [positionMs], or -1 if it is before the first.
     *
     * A position exactly on a boundary belongs to the sentence that *starts* there:
     * the sync file guarantees `chunks[n].end == chunks[n+1].start`, so the
     * alternative would highlight the sentence that has just finished.
     *
     * Past the end of the audio the last sentence stays selected rather than the
     * highlight vanishing, which is what a listener expects at the end of a book.
     */
    fun indexAt(positionMs: Long): Int {
        if (starts.isEmpty()) return -1
        val target = positionMs + offsetMs
        if (target < starts[0]) return -1

        var low = 0
        var high = starts.size - 1
        var answer = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= target) {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return answer
    }

    fun chunkAt(positionMs: Long): SyncChunkEntity? = indexAt(positionMs).takeIf { it >= 0 }?.let { chunks[it] }

    fun chunkAtIndex(index: Int): SyncChunkEntity? = chunks.getOrNull(index)

    /**
     * Where to seek to play a sentence from its start.
     *
     * The offset is subtracted back out: it exists to correct the *lookup*, and
     * applying it to a seek as well would shift playback by it.
     */
    fun seekPositionFor(index: Int): Long? =
        chunks.getOrNull(index)?.let { (it.startMs - offsetMs).coerceAtLeast(0) }

    /** The sentence after the one playing, for a "next sentence" control. */
    fun nextIndex(positionMs: Long): Int? =
        (indexAt(positionMs) + 1).takeIf { it in chunks.indices }

    /**
     * The previous sentence — or the start of the current one if playback is
     * already well into it, which is what "back" means on a media control.
     */
    fun previousIndex(positionMs: Long, restartThresholdMs: Long = 1_500): Int? {
        val current = indexAt(positionMs)
        if (current < 0) return null
        val chunk = chunks[current]
        val into = positionMs + offsetMs - chunk.startMs
        return if (into > restartThresholdMs) current else (current - 1).takeIf { it >= 0 }
    }

    /** A copy with a different timing offset; the mapping itself is unchanged. */
    fun withOffset(offsetMs: Long): ChunkIndex =
        if (offsetMs == this.offsetMs) this else ChunkIndex(chunks, offsetMs)

    companion object {
        val EMPTY = ChunkIndex(emptyList())
    }
}
