package com.folio.reader.ai

import com.folio.reader.data.BookRepository
import com.folio.reader.data.FrontMatter
import com.folio.reader.data.TocPosition
import com.folio.reader.data.TocPositions
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
    val bookId: String = "",
    /**
     * Built to use fewer tokens and actually trimmed: recent pages plus passages about what was asked, instead of
     * everything up to the reader's position that fits the model (which [fullChars] measures).
     */
    val saver: Boolean = false,
    val fullChars: Int = 0,
) {
    /** Characters of book text in this context, sent with the question. */
    val sentChars get() = priorChapters.length + currentChapter.length
}

/** What the context builder reads from the library; [BookRepository] in the app, a fake in tests. */
interface BookTextSource {
    suspend fun chapterText(bookId: String, spineIndex: Int): String
    suspend fun storyStart(bookId: String): FrontMatter.StoryStart?
    /** Other names the reader's character map knows for [name] (nicknames, full name), up to [progress]. */
    suspend fun aliasesFor(bookId: String, name: String, progress: Float): List<String>
    /** Where each contents entry starts in the text, sorted ([TocPositions]). */
    suspend fun sections(bookId: String): List<TocPosition>
}

/** Latest exact text of the current chapter up to the end of the visible page, captured from the reader WebView. */
class ReadingSnapshotCache {
    data class Snapshot(val spineIndex: Int, val textUpToPage: String, val progress: Float)

    private val snapshots = ConcurrentHashMap<String, Snapshot>()
    fun put(bookId: String, snapshot: Snapshot) { snapshots[bookId] = snapshot }
    fun get(bookId: String): Snapshot? = snapshots[bookId]
}

class SpoilerFreeContextBuilder(private val books: BookTextSource) {

    /**
     * @param currentPartial exact text of the current chapter up to the cut-off, as rendered. When null we fall
     * back to the stored chapter text cut at the saved reading fraction, trimmed back to a sentence boundary.
     * @param focus what the reader asked about (the selected text). When early chapters don't fit [maxChars] (small
     * local models), passages from them that mention it are kept, starting with its first appearance.
     * @param saver use fewer tokens: send at most [SAVER_FOCUS_CHARS] (about a selection: recent pages plus the
     * passages that mention it, by any name the character map knows) or [SAVER_CHAT_CHARS] (open chat: recent
     * pages) instead of everything up to the reader's position.
     */
    suspend fun build(
        book: BookEntity,
        chapters: List<ChapterEntity>,
        spineIndex: Int,
        progress: Float,
        currentPartial: String?,
        maxChars: Int,
        focus: String? = null,
        saver: Boolean = false,
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
        // not after its first section ("FROM THE PAGES OF…"), when the contents don't say more.
        fun name(ch: ChapterEntity) = start?.takeIf { it.spineIndex == ch.spineIndex && it.title.isNotBlank() }?.title ?: label(ch)

        // Which chapter a position is in, from where the contents entries start in the text: one file often holds
        // many chapters. Where labels repeat (every Part has a "CHAPTER SIX"), how far into the book says which one.
        val sections = books.sections(book.id)
        val ambiguous = TocPositions.labelsRepeat(sections)
        val charsBefore = LongArray(chapters.size + 1).also { a -> chapters.forEachIndexed { i, ch -> a[i + 1] = a[i] + ch.charCount } }
        fun place(spine: Int, offset: Int, fallback: String): String {
            val label = TocPositions.labelAt(sections, spine, offset) ?: return fallback
            if (!ambiguous) return label
            val percent = (charsBefore[spine.coerceIn(0, chapters.size)] + offset) * 100 / book.totalChars.coerceAtLeast(1)
            return "$label, $percent% into the book"
        }

        /** [base]: where [text] starts in the spine item's stored text (after any front matter left out). */
        data class Prior(val chapter: ChapterEntity, val text: String, val base: Int, val name: String) {
            val block = "<chapter n=\"${chapter.spineIndex + 1}\" title=\"$name\">\n$text\n</chapter>"
        }
        val prior = ArrayDeque<Prior>()
        for (ch in chapters.take(spineIndex)) {
            val stored = books.chapterText(book.id, ch.spineIndex)
            val text = storyText(start, ch.spineIndex, stored)
            val base = stored.length - text.length
            if (text.isNotBlank()) prior += Prior(ch, text, base, place(ch.spineIndex, base, name(ch)))
        }

        // Keep within the model's context window: drop the earliest chapters first, keeping room for excerpts
        // about the focus term from the chapters that get dropped.
        val term = focus?.trim()?.take(80)?.takeIf { it.length >= 2 }
        val aliases = term?.let { books.aliasesFor(book.id, it, progress) }.orEmpty()
        val saverBudget = if (term != null) SAVER_FOCUS_CHARS else SAVER_CHAT_CHARS
        // Only when the model could read much more: a small local model's window is already about this size.
        val saving = saver && maxChars >= 2 * saverBudget
        val budget = if (saving) saverBudget else maxChars
        // When saving tokens, the passages about the selection matter more than a longer stretch of recent pages.
        val excerptBudget = if (term == null) 0 else if (saving) budget * 45 / 100 else budget / 4
        val removed = mutableListOf<Prior>()
        var total = prior.sumOf { it.block.length } + currentText.length
        val fullChars = prior.sumOf { it.text.length } + currentText.length
        // Room for excerpts is only kept when something has to be left out; a book that fits is sent whole.
        val reserve = if (total > budget) excerptBudget else 0
        while (total > budget - reserve && prior.isNotEmpty()) {
            val first = prior.removeFirst()
            total -= first.block.length
            removed += first
        }
        // The current chapter alone may not fit either (some books are a few huge "parts"): its beginning is cut too.
        val currentBudget = budget - reserve
        val currentHead = currentText.dropLast(currentBudget)
        // Chapters are dropped whole, which can leave much of the budget unused (and, early in a chapter, almost no
        // recent text): fill it with the end of the last chapter dropped, the pages right before.
        var tail: Prior? = null
        val room = budget - reserve - total
        val lastDropped = removed.lastOrNull()
        if (lastDropped != null && room > MIN_TAIL_CHARS) {
            val cut = lastDropped.text.length - (room - 200)
            val from = lastDropped.text.indexOf('\n', cut).let { if (it >= 0 && it < cut + 2_000) it + 1 else cut }
            val tailBase = lastDropped.base + from
            tail = lastDropped.copy(
                text = "…" + lastDropped.text.substring(from),
                base = tailBase,
                name = place(lastDropped.chapter.spineIndex, tailBase, lastDropped.name),
            )
            removed[removed.lastIndex] = lastDropped.copy(text = lastDropped.text.substring(0, from))
        }
        // Each passage is labeled with the chapter it's in, so the AI can say where something first appeared.
        val currentBase = rawCurrent.length - currentText.length
        val excerptSources = removed.map { p -> ExcerptSource(p.text) { at -> place(p.chapter.spineIndex, p.base + at, p.name) } } +
            listOfNotNull(currentHead.takeIf { it.isNotBlank() }?.let { head ->
                ExcerptSource(head) { at -> place(spineIndex, currentBase + at, "${current?.let { name(it) }.orEmpty()} (earlier pages)") }
            })
        val excerpts = if (term != null && excerptSources.isNotEmpty()) {
            excerptsIn(term, excerptSources, excerptBudget, aliases)
        } else ""

        val dropped = removed.size
        val trimmed = dropped > 0 || currentHead.isNotEmpty()
        // Trimmed only because of the saver budget, not because the model's window is too small.
        val savedTokens = saving && trimmed
        val leftOut = when {
            dropped == 0 -> "the start of this chapter was left out"
            dropped == 1 -> "the first chapter was left out"
            else -> "the first $dropped chapters were left out"
        }
        val note = when {
            !trimmed -> null
            savedTokens && excerpts.isNotEmpty() ->
                "To use fewer tokens, the AI read the recent pages and the passages that mention \"$term\"."
            savedTokens -> "To use fewer tokens, the AI read only the recent pages."
            excerpts.isNotEmpty() -> "This book is too long for this AI: $leftOut, except passages that mention \"$term\"."
            else -> "This book is too long for this AI: $leftOut."
        }

        // The chapter the reader is in: where their page ends.
        val here = TocPositions.labelAt(sections, spineIndex, rawCurrent.length)
        val currentBlock = buildString {
            append("<chapter n=\"${spineIndex + 1}\" title=\"${place(spineIndex, rawCurrent.length, current?.let { name(it) }.orEmpty())}\" status=\"in progress\">\n")
            append(currentText.takeLast(currentBudget))
            append("\n$READER_MARKER The book's text ends at this point for you.]\n</chapter>")
        }

        return BookContext(
            bookTitle = book.title,
            author = book.author,
            priorChapters = (listOf(excerpts).filter(String::isNotEmpty) + listOfNotNull(tail?.block) + prior.map { it.block })
                .joinToString("\n\n"),
            currentChapter = currentBlock,
            positionLabel = here?.let { positionLabel(it, progress) } ?: positionLabel(current, spineIndex, progress),
            spineIndex = spineIndex,
            progress = progress,
            truncationNote = note,
            bookId = book.id,
            saver = savedTokens,
            fullChars = minOf(fullChars, maxChars),
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

        /** Text to take passages from; [labelAt] names the chapter a position in it is in. */
        class ExcerptSource(val text: String, val labelAt: (Int) -> String)

        /** [excerptsIn] for (chapter label, text) pairs. */
        internal fun excerptsMentioning(
            term: String,
            chapters: List<Pair<String, String>>,
            budget: Int,
            aliases: List<String> = emptyList(),
        ): String = excerptsIn(term, chapters.map { (label, text) -> ExcerptSource(text) { label } }, budget, aliases)

        /**
         * Passages (about [EXCERPT_RADIUS] chars on each side) from [sources] that mention [term] or one of its
         * [aliases], ignoring case and accents: the first mention always, then mentions spread evenly through the
         * rest, within [budget] chars. Each is labeled with the chapter it's in.
         */
        internal fun excerptsIn(
            term: String,
            sources: List<ExcerptSource>,
            budget: Int,
            aliases: List<String> = emptyList(),
        ): String {
            val names = (listOf(term) + aliases).filter { it.isNotBlank() }.distinctBy { SearchController.fold(it).trim() }
            val pattern = Regex(names.joinToString("|") { "(?:${SearchController.patternFor(it).pattern})" })
            data class Hit(val source: ExcerptSource, val at: Int) {
                val text get() = source.text
                val chapter get() = source.labelAt(at)
            }
            val hits = sources.flatMap { source ->
                pattern.findAll(SearchController.fold(source.text)).map { Hit(source, it.range.first) }.toList()
            }
            if (hits.isEmpty()) return ""
            val perExcerpt = 2 * EXCERPT_RADIUS + 80
            val count = (budget / perExcerpt).coerceIn(1, hits.size)
            // Indices spread over all hits, always including the first one (where the term first appears).
            val picked = (0 until count).map { i -> if (count == 1) 0 else i * (hits.size - 1) / (count - 1) }.distinct()
            return buildString {
                append("<excerpts note=\"Earlier chapters were left out to save space; these passages from them mention '")
                append(term.replace("\"", "'")).append("'")
                if (names.size > 1) append(" (also called: ").append(names.drop(1).joinToString(", ").replace("\"", "'")).append(")")
                append(".\">\n")
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

        /** Below this, unused budget isn't worth a partial chapter. */
        private const val MIN_TAIL_CHARS = 2_000

        /** Saving tokens, about a selection: ~15K tokens, of which 45% are passages about it. */
        const val SAVER_FOCUS_CHARS = 60_000

        /** Saving tokens, open chat ("Where was I?"): the last ~40 pages, ~20K tokens. */
        const val SAVER_CHAT_CHARS = 80_000

        /** Rough token count for [chars] characters of text (about 4 per token in English and Spanish prose). */
        fun tokensFor(chars: Int) = (chars + 3) / 4

        /** Opening of the cut-off marker; the prompts refer to it as "[THE READER IS HERE ...]". */
        const val READER_MARKER = "[THE READER IS HERE."

        fun label(ch: ChapterEntity) = ch.title.ifBlank { "Section ${ch.spineIndex + 1}" }

        fun positionLabel(ch: ChapterEntity?, spineIndex: Int, progress: Float): String =
            positionLabel(ch?.let(::label) ?: "Section ${spineIndex + 1}", progress)

        fun positionLabel(chapterTitle: String, progress: Float) = "$chapterTitle · ${(progress * 100).toInt()}% of the book"

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
