package com.folio.reader.data

import com.folio.reader.ai.BookTextSource
import com.folio.reader.ai.SpoilerFreeContextBuilder
import com.folio.reader.data.db.BookEntity
import com.folio.reader.data.db.ChapterEntity
import com.folio.reader.data.db.TocEntity
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TocPositionsTest {

    private fun entry(ord: Int, title: String, spine: Int, fragment: String?, depth: Int = 0) =
        TocEntity(bookId = "b", ord = ord, title = title, depth = depth, spineIndex = spine, fragment = fragment)

    /** Several chapters in one file (as Gutenberg does): each entry's offset lands on its heading in the extracted text. */
    @Test
    fun offsetsOfChaptersInsideOneFile() {
        val html = """<html><body>
            <h1>PRIDE and PREJUDICE</h1><p>By Jane Austen.</p>
            <h2><a id="c1"></a>CHAPTER I.</h2><p>It is a truth universally acknowledged…</p>
            <h2 id="c2">CHAPTER II.</h2><p>Mr. Bennet was among the earliest…</p>
            <p>Some text with an <a name="note"></a>inline anchor.</p>
            </body></html>"""
        val text = BookRepository.normalizeText(Jsoup.parse(html).body().wholeText())
        val entries = listOf(entry(0, "PRIDE and PREJUDICE", 1, null), entry(1, "CHAPTER I.", 1, "c1"), entry(2, "CHAPTER II.", 1, "c2"),
            entry(3, "Note", 1, "note"), entry(4, "Missing", 1, "nope"))
        val positions = TocPositions.inDocument(Jsoup.parse(html), entries).sortedBy { it.offset }
        assertEquals(listOf("PRIDE and PREJUDICE", "CHAPTER I.", "CHAPTER II.", "Note"), positions.map { it.title })
        assertEquals(0, positions[0].offset)
        assertTrue(text.substring(positions[1].offset).startsWith("CHAPTER I."))
        assertTrue(text.substring(positions[2].offset).startsWith("CHAPTER II."))
        assertTrue(text.substring(positions[3].offset).trimStart().startsWith("inline anchor"))
    }

    @Test
    fun labelAt_findsTheChapterOfAPosition() {
        val positions = listOf(
            TocPosition(1, 0, "PART ONE"), TocPosition(1, 100, "CHAPTER ONE", depth = 1), TocPosition(1, 5_000, "CHAPTER TWO", depth = 1),
            TocPosition(3, 200, "PART TWO"), TocPosition(3, 300, "CHAPTER ONE", depth = 1),
        )
        assertNull(TocPositions.labelAt(positions, 0, 50))
        assertEquals("PART ONE · CHAPTER ONE", TocPositions.labelAt(positions, 1, 4_999))
        assertEquals("PART ONE · CHAPTER TWO", TocPositions.labelAt(positions, 1, 5_000))
        // A file that starts mid-chapter continues the last chapter of the file before.
        assertEquals("PART ONE · CHAPTER TWO", TocPositions.labelAt(positions, 2, 10))
        assertEquals("PART ONE · CHAPTER TWO", TocPositions.labelAt(positions, 3, 150))
        assertEquals("PART TWO · CHAPTER ONE", TocPositions.labelAt(positions, 3, 400))
        // With the parts in the contents, "CHAPTER ONE" twice isn't ambiguous; without them it is.
        assertFalse(TocPositions.labelsRepeat(positions))
        assertTrue(TocPositions.labelsRepeat(positions.filter { it.depth == 1 }.map { it.copy(depth = 0) }))
    }

    /**
     * An edition like the reader's Crime and Punishment: a whole Part per file and a "CHAPTER ONE" in every Part. The AI
     * must be told the passage is in chapter two (and how far into the book), not "CHAPTER ONE" (the file's first entry).
     */
    @Test
    fun passagesAndPosition_areLabeledWithTheRealChapter() {
        val filler = "Rain on the canal, the clerk at his desk, the stairs again. "
        val part1 = "CHAPTER ONE\n" + filler.repeat(800) + "\nCHAPTER TWO\n" + filler.repeat(200) +
            "Katerina Ivanovna coughed in the doorway. " + filler.repeat(600) + "\nCHAPTER THREE\n" + filler.repeat(800)
        val part2 = "CHAPTER ONE\n" + filler.repeat(900)
        val c2 = part1.indexOf("CHAPTER TWO")
        val c3 = part1.indexOf("CHAPTER THREE")
        val source = object : BookTextSource {
            override suspend fun chapterText(bookId: String, spineIndex: Int) = if (spineIndex == 0) part1 else part2
            override suspend fun storyStart(bookId: String): FrontMatter.StoryStart? = null
            override suspend fun aliasesFor(bookId: String, name: String, progress: Float) = emptyList<String>()
            override suspend fun sections(bookId: String) = listOf(
                TocPosition(0, 0, "CHAPTER ONE"), TocPosition(0, c2, "CHAPTER TWO"), TocPosition(0, c3, "CHAPTER THREE"),
                TocPosition(1, 0, "CHAPTER ONE"),
            )
        }
        val chapters = listOf(ChapterEntity("b", 0, "p1.html", "CHAPTER ONE", part1.length), ChapterEntity("b", 1, "p2.html", "CHAPTER ONE", part2.length))
        val book = BookEntity(id = "b", title = "Crime", author = "F. D.", language = "en", coverPath = null, addedAt = 0,
            totalChars = (part1.length + part2.length).toLong())
        val ctx = runBlocking {
            SpoilerFreeContextBuilder(source).build(book, chapters, 1, 0.8f, part2.take(40_000), 2_800_000, focus = "Katerina Ivanovna", saver = true)
        }
        // How far into the book the passage itself is.
        val percent = part1.indexOf("Katerina Ivanovna") * 100L / book.totalChars
        assertTrue(ctx.priorChapters, ctx.priorChapters.contains("[CHAPTER TWO, ${percent}% into the book] …"))
        assertFalse(ctx.priorChapters.contains("[CHAPTER ONE"))
        assertEquals("CHAPTER ONE · 80% of the book", ctx.positionLabel)
        assertTrue(ctx.currentChapter.contains("title=\"CHAPTER ONE, "))
    }
}
