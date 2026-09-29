package dev.reedd.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Insert
    suspend fun insert(note: NoteEntity): Long

    /** Book order: chapter first (spineIndex), then position within it (progression). */
    @Query("SELECT * FROM notes WHERE bookId = :bookId ORDER BY spineIndex ASC, progression ASC, id ASC")
    fun observe(bookId: String): Flow<List<NoteEntity>>

    /** The editor's own two editable fields -- everything else about a note
     *  (what it quotes, where it points, when it was made) describes the
     *  moment it was created and stays fixed across an edit. */
    @Query("UPDATE notes SET noteText = :noteText, type = :type WHERE id = :id")
    suspend fun update(id: Long, noteText: String, type: BookmarkType)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)
}
