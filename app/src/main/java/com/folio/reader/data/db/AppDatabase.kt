package com.folio.reader.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        TocEntity::class,
        HighlightEntity::class,
        BookmarkEntity::class,
        CollectionEntity::class,
        CollectionBookRef::class,
        CharacterGraphEntity::class,
        ReadingDayEntity::class,
        EncyclopediaEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun annotations(): AnnotationDao
    abstract fun collections(): CollectionDao
    abstract fun graphs(): GraphDao
    abstract fun stats(): StatsDao
    abstract fun encyclopedias(): EncyclopediaDao

    companion object {
        /** v2: books.format (PDF and comic support). Existing books are all EPUB. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN format TEXT NOT NULL DEFAULT 'epub'")
            }
        }

        /** v3: reading statistics and the book encyclopedia. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reading_days` (`date` TEXT NOT NULL, `bookId` TEXT NOT NULL, " +
                        "`seconds` INTEGER NOT NULL, `pages` INTEGER NOT NULL, `chars` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`date`, `bookId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reading_days_bookId` ON `reading_days` (`bookId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `encyclopedias` (`bookId` TEXT NOT NULL, `json` TEXT NOT NULL, " +
                        "`spineIndex` INTEGER NOT NULL, `progress` REAL NOT NULL, `positionLabel` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`bookId`), FOREIGN KEY(`bookId`) REFERENCES " +
                        "`books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
            }
        }

        const val NAME = "folio.db"

        /**
         * No destructive fallback: a missing migration must fail loudly instead of silently wiping the reader's
         * highlights, notes and progress. Before any upgrade, the old file is copied aside just in case.
         */
        fun build(context: Context): AppDatabase {
            snapshotBeforeUpgrade(context)
            return Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }

        private fun snapshotBeforeUpgrade(context: Context) {
            val db = context.getDatabasePath(NAME)
            if (!db.exists()) return
            val version = runCatching {
                SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
            }.getOrNull() ?: return
            if (version >= VERSION) return
            val dir = File(context.filesDir, SNAPSHOT_DIR).apply { mkdirs() }
            val stamp = System.currentTimeMillis()
            for (suffix in listOf("", "-wal", "-shm")) {
                val src = File(db.path + suffix)
                if (src.exists()) src.copyTo(File(dir, "folio-v$version-$stamp.db$suffix"), overwrite = true)
            }
            // Keep the three most recent snapshots.
            dir.listFiles { f -> f.name.endsWith(".db") }.orEmpty().sortedByDescending { it.lastModified() }.drop(3).forEach { old ->
                listOf("", "-wal", "-shm").forEach { File(old.path + it).delete() }
            }
        }

        const val VERSION = 3
        private const val SNAPSHOT_DIR = "db-snapshots"
    }
}
