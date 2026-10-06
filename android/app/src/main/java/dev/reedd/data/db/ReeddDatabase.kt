package dev.reedd.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [BookEntity::class, SyncChunkEntity::class, SyncChapterEntity::class, NoteEntity::class],
    version = 18,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ReeddDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun sync(): SyncDao
    abstract fun notes(): NoteDao

    companion object {
        /**
         * Migrations are declared explicitly and there is still no destructive
         * fallback: wiping a library of converted audiobooks on a schema bump
         * would be far worse than a crash naming the missing migration.
         * `app/schemas/` is checked in so each version has something to diff
         * against.
         */
        fun create(context: Context): ReeddDatabase =
            Room.databaseBuilder(context, ReeddDatabase::class.java, "reedd.db")
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                    MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18,
                )
                .build()
    }
}
