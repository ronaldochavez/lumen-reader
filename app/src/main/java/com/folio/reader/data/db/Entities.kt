package com.folio.reader.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val language: String?,
    /** Absolute path to the cover image inside the extracted book, if any. */
    val coverPath: String?,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
    /** Reading position: spine item + text offset of the first char on the current page. */
    val spineIndex: Int = 0,
    val chapterOffset: Int = 0,
    /** Fraction of the current chapter read (end of current page). */
    val chapterFraction: Float = 0f,
    /** Fraction of the whole book read, by characters. */
    val progress: Float = 0f,
    val totalChars: Long,
    /** [BookFormat.id]: reflowable text (EPUB or converted), PDF pages, or comic images. */
    val format: String = BookFormat.REFLOW.id,
)

enum class BookFormat(val id: String) {
    REFLOW("epub"),
    PDF("pdf"),
    COMIC("comic");

    /** Formats shown as fixed page images instead of reflowable text. */
    val isFixedLayout get() = this != REFLOW

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: REFLOW
    }
}

@Entity(
    tableName = "chapters",
    primaryKeys = ["bookId", "spineIndex"],
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class ChapterEntity(
    val bookId: String,
    val spineIndex: Int,
    /** Path relative to the extracted book root. */
    val href: String,
    val title: String,
    val charCount: Int,
)

@Entity(
    tableName = "toc",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")],
)
data class TocEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val ord: Int,
    val title: String,
    val depth: Int,
    val spineIndex: Int,
    val fragment: String?,
)

@Entity(
    tableName = "highlights",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")],
)
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val spineIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val color: String,
    val note: String? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val spineIndex: Int,
    val offset: Int,
    val progress: Float,
    val snippet: String,
    val createdAt: Long,
)

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "collection_books",
    primaryKeys = ["collectionId", "bookId"],
    foreignKeys = [
        ForeignKey(CollectionEntity::class, ["id"], ["collectionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("bookId")],
)
data class CollectionBookRef(
    val collectionId: Long,
    val bookId: String,
)

data class CollectionWithBooks(
    @Embedded val collection: CollectionEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(CollectionBookRef::class, parentColumn = "collectionId", entityColumn = "bookId"),
    )
    val books: List<BookEntity>,
)

/**
 * Reading activity per day and book. No foreign key on purpose: deleting a book shouldn't erase the
 * history behind your streak.
 */
@Entity(tableName = "reading_days", primaryKeys = ["date", "bookId"], indices = [Index("bookId")])
data class ReadingDayEntity(
    /** Local date, ISO yyyy-MM-dd. */
    val date: String,
    val bookId: String,
    val seconds: Long,
    val pages: Int,
    /** Characters advanced (reflowable/PDF text), used to estimate reading speed. */
    val chars: Long,
)

data class DayTotal(val date: String, val seconds: Long, val pages: Int, val chars: Long)

data class ReadingTotals(val seconds: Long?, val pages: Int?, val chars: Long?)

@Entity(
    tableName = "encyclopedias",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class EncyclopediaEntity(
    @PrimaryKey val bookId: String,
    val json: String,
    val spineIndex: Int,
    val progress: Float,
    val positionLabel: String,
    val createdAt: Long,
)

@Entity(
    tableName = "character_graphs",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class CharacterGraphEntity(
    @PrimaryKey val bookId: String,
    val json: String,
    val spineIndex: Int,
    val progress: Float,
    val positionLabel: String,
    val createdAt: Long,
)
