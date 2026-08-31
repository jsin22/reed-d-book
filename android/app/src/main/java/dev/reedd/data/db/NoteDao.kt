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

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)
}
