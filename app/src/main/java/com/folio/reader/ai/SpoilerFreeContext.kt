package com.folio.reader.ai

import com.folio.reader.data.BookRepository
import com.folio.reader.data.FrontMatter
import com.folio.reader.data.db.BookEntity
import com.folio.reader.data.db.ChapterEntity
import com.folio.reader.ui.reader.SearchController
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
    /** Human label of the cut-off point, e.g. "Chapter 7 · 36% of the book". */
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
     * @param focus what the reader asked about (the selected text). When early chapters don't fit [maxChars] (small
     * local models), passages from them that mention it are kept, starting with its first appearance.
     */
    suspend fun build(
        book: BookEntity,
        chapters: List<ChapterEntity>,
        spineIndex: Int,
        progress: Float,
        currentPartial: String?,
        maxChars: Int,
        focus: String? = null,
    ): BookContext {
        val current = chapters.getOrNull(spineIndex)
        val rawCurrent = currentPartial?.let(BookRepository::normalizeText)
            ?: cutAtFraction(books.chapterText(book.id, spineIndex), book.chapterFraction)
        // An introduction or similar before the story is left out once the reader is past it (it may tell the plot).
        val start = books.storyStart(book.id)?.takeIf { s ->
            spineIndex > s.spineIndex || (spineIndex == s.spineIndex && rawCurrent.length > s.offset)
        }
        val currentText = storyText(start, spineIndex, rawCurrent)

        // A spine item that opens with front matter is named after where its story text begins ("CHAPTER ONE"),
        // not after its first section ("FROM THE PAGES OF…").
        fun name(ch: ChapterEntity) = start?.takeIf { it.spineIndex == ch.spineIndex && it.title.isNotBlank() }?.title ?: label(ch)
        data class Prior(val chapter: ChapterEntity, val text: String, val name: String) {
            val block = "<chapter n=\"${chapter.spineIndex + 1}\" title=\"$name\">\n$text\n</chapter>"
        }
        val prior = ArrayDeque<Prior>()
        for (ch in chapters.take(spineIndex)) {
            val text = storyText(start, ch.spineIndex, books.chapterText(book.id, ch.spineIndex))
            if (text.isNotBlank()) prior += Prior(ch, text, name(ch))
        }

        // Keep within the model's context window: drop the earliest chapters first, keeping room for excerpts
        // about the focus term from the chapters that get dropped.
        val term = focus?.trim()?.take(80)?.takeIf { it.length >= 2 }
        val excerptBudget = if (term != null) maxChars / 4 else 0
        val removed = mutableListOf<Prior>()
        var total = prior.sumOf { it.block.length } + currentText.length
        while (total > maxChars - excerptBudget && prior.isNotEmpty()) {
            val first = prior.removeFirst()
            total -= first.block.length
            removed += first
        }
        // The current chapter alone may not fit either (some books are a few huge "parts"): its beginning is cut too.
        val currentBudget = maxChars - excerptBudget
        val currentHead = currentText.dropLast(currentBudget)
        val excerptSources = removed.map { it.name to it.text } +
            listOfNotNull(currentHead.takeIf { it.isNotBlank() }?.let { "${current?.let { name(it) }.orEmpty()} (earlier pages)" to it })
        val excerpts = if (term != null && excerptSources.isNotEmpty()) {
            excerptsMentioning(term, excerptSources, excerptBudget)
        } else ""

        val dropped = removed.size
        val leftOut = when {
            dropped == 0 -> "the start of this chapter was left out"
            dropped == 1 -> "the first chapter was left out"
            else -> "the first $dropped chapters were left out"
        }
        val note = when {
            dropped == 0 && currentHead.isEmpty() -> null
            excerpts.isNotEmpty() -> "This book is too long for this AI: $leftOut, except passages that mention \"$term\"."
            else -> "This book is too long for this AI: $leftOut."
        }

        val currentBlock = buildString {
            append("<chapter n=\"${spineIndex + 1}\" title=\"${current?.let { name(it) }.orEmpty()}\" status=\"in progress\">\n")
            append(currentText.takeLast(currentBudget))
            append("\n$READER_MARKER The book's text ends at this point for you.]\n</chapter>")
        }

        return BookContext(
            bookTitle = book.title,
            author = book.author,
            priorChapters = listOf(excerpts).filter(String::isNotEmpty).plus(prior.map { it.block }).joinToString("\n\n"),
            currentChapter = currentBlock,
            positionLabel = positionLabel(current, spineIndex, progress),
            spineIndex = spineIndex,
            progress = progress,
            truncationNote = note,
        )
    }

    companion object {
        /**
         * The part of a spine item's [text] that belongs to the story when it starts at [start]: nothing before its
         * spine item, and in that item only the text from the start on.
         */
        internal fun storyText(start: FrontMatter.StoryStart?, spine: Int, text: String): String = when {
            start == null || spine > start.spineIndex -> text
            spine < start.spineIndex -> ""
            else -> text.drop(start.offset).trimStart()
        }

        /**
         * Passages (about [EXCERPT_RADIUS] chars on each side) from [chapters] that mention [term], ignoring case and
         * accents: the first mention always, then mentions spread evenly through the rest, within [budget] chars.
         */
        internal fun excerptsMentioning(term: String, chapters: List<Pair<String, String>>, budget: Int): String {
            val pattern = SearchController.patternFor(term)
            data class Hit(val chapter: String, val text: String, val at: Int)
            val hits = chapters.flatMap { (label, text) ->
                pattern.findAll(SearchController.fold(text)).map { Hit(label, text, it.range.first) }.toList()
            }
            if (hits.isEmpty()) return ""
            val perExcerpt = 2 * EXCERPT_RADIUS + 80
            val count = (budget / perExcerpt).coerceIn(1, hits.size)
            // Indices spread over all hits, always including the first one (where the term first appears).
            val picked = (0 until count).map { i -> if (count == 1) 0 else i * (hits.size - 1) / (count - 1) }.distinct()
            return buildString {
                append("<excerpts note=\"Earlier chapters were left out to save space; these passages from them mention '")
                append(term.replace("\"", "'")).append("'.\">\n")
                var lastEnd = -1
                var lastText: String? = null
                for (i in picked) {
                    val h = hits[i]
                    var start = (h.at - EXCERPT_RADIUS).coerceAtLeast(0)
                    val end = (h.at + EXCERPT_RADIUS).coerceAtMost(h.text.length)
                    if (h.text === lastText && start < lastEnd) start = lastEnd // don't repeat overlapping text
                    if (start >= end) continue
                    // Start and end on word boundaries.
                    val s = if (start == 0) 0 else h.text.indexOf(' ', start).let { if (it in start until end) it + 1 else start }
                    val e = if (end == h.text.length) end else h.text.lastIndexOf(' ', end).let { if (it > s) it else end }
                    append("[").append(h.chapter).append("] …").append(h.text.substring(s, e).trim()).append("…\n")
                    lastEnd = end
                    lastText = h.text
                }
                append("</excerpts>")
            }
        }

        private const val EXCERPT_RADIUS = 450

        /** Opening of the cut-off marker; the prompts refer to it as "[THE READER IS HERE ...]". */
        const val READER_MARKER = "[THE READER IS HERE."

        fun label(ch: ChapterEntity) = ch.title.ifBlank { "Section ${ch.spineIndex + 1}" }

        fun positionLabel(ch: ChapterEntity?, spineIndex: Int, progress: Float): String {
            val name = ch?.let(::label) ?: "Section ${spineIndex + 1}"
            return "$name · ${(progress * 100).toInt()}% of the book"
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
