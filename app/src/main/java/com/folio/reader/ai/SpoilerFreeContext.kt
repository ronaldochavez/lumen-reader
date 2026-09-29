package com.folio.reader.ai

import com.folio.reader.data.BookRepository
import com.folio.reader.data.db.BookEntity
import com.folio.reader.data.db.ChapterEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * The book text from the very beginning up to the reader's position — nothing after it.
 *
 * [priorChapters] (all fully-read chapters) is split from [currentChapter] (the partial chapter) so the
 * former can be prompt-cached: it only changes when the reader crosses into a new chapter.
 */
data class BookContext(
    val bookTitle: String,
    val author: String,
    val priorChapters: String,
    val currentChapter: String,
    /** Human label of the cut-off point, e.g. "Capítulo 7 · 36% del libro". */
    val positionLabel: String,
    val spineIndex: Int,
    val progress: Float,
    /** Set when early chapters had to be dropped to fit the model's context window. */
    val truncationNote: String? = null,
)

/** Latest exact text of the current chapter up to the end of the visible page, captured from the reader WebView. */
class ReadingSnapshotCache {
    data class Snapshot(val spineIndex: Int, val textUpToPage: String, val progress: Float)

    private val snapshots = ConcurrentHashMap<String, Snapshot>()
    fun put(bookId: String, snapshot: Snapshot) { snapshots[bookId] = snapshot }
    fun get(bookId: String): Snapshot? = snapshots[bookId]
}

class SpoilerFreeContextBuilder(private val books: BookRepository) {

    /**
     * @param currentPartial exact text of the current chapter up to the cut-off, as rendered. When null we fall
     * back to the stored chapter text cut at the saved reading fraction, trimmed back to a sentence boundary.
     */
    suspend fun build(
        book: BookEntity,
        chapters: List<ChapterEntity>,
        spineIndex: Int,
        progress: Float,
        currentPartial: String?,
        maxChars: Int,
    ): BookContext {
        val current = chapters.getOrNull(spineIndex)
        val currentText = currentPartial?.let(BookRepository::normalizeText)
            ?: cutAtFraction(books.chapterText(book.id, spineIndex), book.chapterFraction)

        val prior = mutableListOf<String>()
        for (ch in chapters.take(spineIndex)) {
            val text = books.chapterText(book.id, ch.spineIndex)
            if (text.isBlank()) continue
            prior += "<capitulo n=\"${ch.spineIndex + 1}\" titulo=\"${label(ch)}\">\n$text\n</capitulo>"
        }

        // Keep within the model's context window; drop the earliest chapters first and say so.
        var dropped = 0
        var total = prior.sumOf { it.length } + currentText.length
        while (total > maxChars && prior.isNotEmpty()) {
            total -= prior.removeAt(0).length
            dropped++
        }

        val currentBlock = buildString {
            append("<capitulo n=\"${spineIndex + 1}\" titulo=\"${current?.let(::label).orEmpty()}\" estado=\"en curso\">\n")
            append(currentText.takeLast(maxChars))
            append("\n[EL LECTOR VA AQUÍ. El texto del libro termina en este punto para ti.]\n</capitulo>")
        }

        return BookContext(
            bookTitle = book.title,
            author = book.author,
            priorChapters = prior.joinToString("\n\n"),
            currentChapter = currentBlock,
            positionLabel = positionLabel(current, spineIndex, progress),
            spineIndex = spineIndex,
            progress = progress,
            truncationNote = if (dropped > 0) "El libro es muy largo: se omitieron los primeros $dropped capítulos." else null,
        )
    }

    companion object {
        fun label(ch: ChapterEntity) = ch.title.ifBlank { "Sección ${ch.spineIndex + 1}" }

        fun positionLabel(ch: ChapterEntity?, spineIndex: Int, progress: Float): String {
            val name = ch?.let(::label) ?: "Sección ${spineIndex + 1}"
            return "$name · ${(progress * 100).toInt()}% del libro"
        }

        /** Cuts [text] at [fraction], then backs up to the previous sentence end so nothing unread leaks. */
        fun cutAtFraction(text: String, fraction: Float): String {
            if (text.isEmpty()) return text
            val cut = (text.length * fraction.coerceIn(0f, 1f)).toInt()
            if (cut >= text.length) return text
            val head = text.substring(0, cut)
            val lastStop = head.indexOfLast { it == '.' || it == '!' || it == '?' || it == '\n' || it == '…' }
            return if (lastStop > 0) head.substring(0, lastStop + 1) else head
        }
    }
}
