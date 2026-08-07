package dev.reedd.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [BookEntity::class, SyncChunkEntity::class, SyncChapterEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ReeddDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun sync(): SyncDao

    companion object {
        /**
         * No migrations are registered yet, and no destructive fallback either:
         * at version 1 there is nothing to migrate from, and silently wiping a
         * user's library on a future schema bump is worse than a crash that says
         * a migration is missing. `app/schemas/` is checked in so the next
         * version has something to diff against.
         */
        fun create(context: Context): ReeddDatabase =
            Room.databaseBuilder(context, ReeddDatabase::class.java, "reedd.db").build()
    }
}
