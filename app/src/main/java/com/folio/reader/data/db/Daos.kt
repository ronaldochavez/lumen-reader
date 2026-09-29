package com.folio.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY COALESCE(lastOpenedAt, addedAt) DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: String): BookEntity?

    @Insert
    suspend fun insert(book: BookEntity)

    @Insert
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Insert
    suspend fun insertToc(entries: List<TocEntity>)

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY spineIndex")
    suspend fun chapters(bookId: String): List<ChapterEntity>

    @Query("SELECT * FROM toc WHERE bookId = :bookId ORDER BY ord")
    suspend fun toc(bookId: String): List<TocEntity>

    @Query(
        "UPDATE books SET spineIndex = :spineIndex, chapterOffset = :offset, chapterFraction = :fraction, " +
            "progress = :progress, lastOpenedAt = :now WHERE id = :id"
    )
    suspend fun updateProgress(id: String, spineIndex: Int, offset: Int, fraction: Float, progress: Float, now: Long)

    @Query("UPDATE books SET lastOpenedAt = :now WHERE id = :id")
    suspend fun markOpened(id: String, now: Long)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE")
    suspend fun all(): List<BookEntity>
}

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY spineIndex, startOffset")
    fun observeHighlights(bookId: String): Flow<List<HighlightEntity>>

    @Insert
    suspend fun insertHighlight(h: HighlightEntity): Long

    @Update
    suspend fun updateHighlight(h: HighlightEntity)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteHighlight(id: Long)

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY spineIndex, offset")
    fun observeBookmarks(bookId: String): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun insertBookmark(b: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id IN (:ids)")
    suspend fun deleteBookmarks(ids: List<Long>)

    @Query("SELECT * FROM highlights ORDER BY bookId, spineIndex, startOffset")
    suspend fun allHighlights(): List<HighlightEntity>

    @Query("SELECT COUNT(*) FROM bookmarks")
    suspend fun bookmarkCount(): Int
}

@Dao
interface CollectionDao {
    @Transaction
    @Query("SELECT * FROM collections ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<CollectionWithBooks>>

    @Transaction
    @Query("SELECT * FROM collections WHERE id = :id")
    fun observe(id: Long): Flow<CollectionWithBooks?>

    @Insert
    suspend fun insert(c: CollectionEntity): Long

    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBook(ref: CollectionBookRef)

    @Query("DELETE FROM collection_books WHERE collectionId = :collectionId AND bookId = :bookId")
    suspend fun removeBook(collectionId: Long, bookId: String)

    @Query("SELECT collectionId FROM collection_books WHERE bookId = :bookId")
    suspend fun collectionIdsFor(bookId: String): List<Long>
}

@Dao
abstract class StatsDao {
    @Query("SELECT * FROM reading_days WHERE date = :date AND bookId = :bookId")
    protected abstract suspend fun get(date: String, bookId: String): ReadingDayEntity?

    @Upsert
    protected abstract suspend fun upsert(day: ReadingDayEntity)

    /** Adds to today's counters (read-modify-write: SQLite upsert syntax needs API 30+). */
    @Transaction
    open suspend fun add(date: String, bookId: String, seconds: Long, pages: Int, chars: Long) {
        val cur = get(date, bookId)
        upsert(
            cur?.copy(seconds = cur.seconds + seconds, pages = cur.pages + pages, chars = cur.chars + chars)
                ?: ReadingDayEntity(date, bookId, seconds, pages, chars)
        )
    }

    @Query("SELECT date, SUM(seconds) AS seconds, SUM(pages) AS pages, SUM(chars) AS chars FROM reading_days GROUP BY date ORDER BY date DESC")
    abstract fun observeDays(): Flow<List<DayTotal>>

    @Query("SELECT SUM(seconds) AS seconds, SUM(pages) AS pages, SUM(chars) AS chars FROM reading_days WHERE bookId = :bookId")
    abstract suspend fun bookTotals(bookId: String): ReadingTotals

    @Query("SELECT SUM(seconds) AS seconds, SUM(pages) AS pages, SUM(chars) AS chars FROM reading_days")
    abstract suspend fun allTotals(): ReadingTotals
}

@Dao
interface EncyclopediaDao {
    @Query("SELECT * FROM encyclopedias WHERE bookId = :bookId")
    fun observe(bookId: String): Flow<EncyclopediaEntity?>

    @Upsert
    suspend fun upsert(e: EncyclopediaEntity)
}

@Dao
interface GraphDao {
    @Query("SELECT * FROM character_graphs WHERE bookId = :bookId")
    fun observe(bookId: String): Flow<CharacterGraphEntity?>

    @Upsert
    suspend fun upsert(graph: CharacterGraphEntity)
}
