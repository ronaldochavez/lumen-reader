package com.folio.reader.data

import com.folio.reader.data.db.TocEntity
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.TextNode
import java.io.File
import java.text.Normalizer

/**
 * Introductions, prefaces, "From the pages of…" teasers and chronologies in classic editions often tell the whole
 * plot, and they come before the story, so they are always "before the reader's page". When a book has such front
 * matter, the text sent to the AI starts where the story does (see [BookRepository.storyStart]).
 */
object FrontMatter {

    /** Where the story begins: a spine item, a character offset in its text, and that contents entry's title. */
    data class StoryStart(val spineIndex: Int, val offset: Int, val title: String = "")

    /**
     * The contents entry where the story begins, or null when there's no spoiler-prone front matter to leave out.
     * That's the first "Part One" / "Chapter 1" / "Prologue"-like entry when an introduction-like entry comes before
     * it; for books whose chapters aren't numbered, the entry right after the last introduction-like one near the
     * start of the book.
     */
    fun storyStartEntry(toc: List<TocEntity>, spineCount: Int): TocEntity? {
        val firstStory = toc.indexOfFirst { isStoryStart(it.title) }
        val start = if (firstStory >= 0) {
            firstStory.takeIf { toc.take(firstStory).any { isSpoilerProne(it.title) } }
        } else {
            val frontRegion = maxOf(1, spineCount / 3)
            toc.indices.lastOrNull { isSpoilerProne(toc[it].title) && toc[it].spineIndex < frontRegion }?.plus(1)
        } ?: return null
        val entry = toc.getOrNull(start) ?: return null
        return entry.takeUnless { start == 0 || (it.spineIndex == 0 && it.fragment.isNullOrBlank()) }
    }

    /** Character offset of the element [fragment] points to, in the text the importer extracts from [html]. */
    fun offsetOf(html: File, fragment: String?): Int? {
        if (fragment.isNullOrBlank()) return 0
        if (!html.exists()) return null
        return runCatching { offsetIn(Jsoup.parse(html, "UTF-8"), fragment) }.getOrNull()
    }

    internal fun offsetIn(doc: Document, fragment: String): Int? {
        val target = doc.getElementById(fragment) ?: doc.getElementsByAttributeValue("name", fragment).firstOrNull()
            ?: return null
        // A marker right before the target, run through the importer's own text extraction (BookRepository.extractText).
        target.before(TextNode(MARK))
        return BookRepository.normalizeText(doc.body().wholeText()).indexOf(MARK).takeIf { it >= 0 }
    }

    private const val MARK = ""

    internal fun normalize(title: String): String =
        Normalizer.normalize(title.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("['’`]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    /**
     * Sections that discuss the whole book, by how the title starts: "Introduction", "An Introduction to…",
     * "Translator's Preface", but not a chapter called "The Introduction of Mr. Darcy".
     */
    private val PRONE_WORDS = Regex(
        "^((a|an|editors|translators|authors|critical|general|historical|biographical) )?" +
            "(introduction|introduccion|introductory|foreword|preface|prefacio|chronology|cronologia|timeline|" +
            "afterword|synopsis|sinopsis)\\b"
    )
    private val PRONE_STARTS = listOf(
        "from the pages of", "the world of", "a note on", "note on the", "notes on the", "translators note",
        "editors note", "about the book", "about this book", "praise for", "reading group", "readers guide",
        "discussion questions", "for further reading", "a conversation with", "inspired by", "comments questions",
        "nota del traductor", "nota del editor", "nota a la", "nota sobre", "estudio preliminar", "presentacion",
    )

    internal fun isSpoilerProne(title: String): Boolean {
        val n = normalize(title)
        return PRONE_WORDS.containsMatchIn(n) || PRONE_STARTS.any { n.startsWith(it) }
    }

    private val NUMBER = "(\\d+|[ivxlc]+|one|first|uno|una|primero|primera|primer)"
    private val STORY_STARTS = listOf(
        // Not "Section": untitled contents entries are stored as "Section N".
        Regex("^(part|book|volume|chapter|canto|act|parte|libro|tomo|volumen|capitulo|acto) $NUMBER\\b"),
        Regex("^(primera parte|first part|part the first|book the first|libro primero)\\b"),
        Regex("^(prologue|prolog|prologo)\\b"),
    )

    /** "Part One", "Chapter 1", "Libro primero", "Prologue", or a bare "1" / "I." / "One" heading. */
    internal fun isStoryStart(title: String): Boolean {
        val n = normalize(title)
        if (STORY_STARTS.any { it.containsMatchIn(n) }) return true
        val raw = title.trim()
        // Bare numerals: "1", "1. Arrival", "I.", "One", but not a title such as "I Am Legend".
        return Regex("^1(\\s|[.:)\\-–—]|$)").containsMatchIn(raw) ||
            Regex("^(I|One|ONE|Uno|UNO)\\s*([.:)\\-–—]|$)").containsMatchIn(raw)
    }
}
