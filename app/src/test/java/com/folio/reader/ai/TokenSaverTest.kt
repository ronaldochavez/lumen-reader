package com.folio.reader.ai

import com.folio.reader.data.FrontMatter
import com.folio.reader.data.db.BookEntity
import com.folio.reader.data.db.ChapterEntity
import com.folio.reader.ui.reader.AskController
import com.folio.reader.ui.reader.tokenLabel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** "Use fewer tokens": what reaches the AI, and the saved answers. */
class TokenSaverTest {

    /** 12 chapters of ~30K characters (a long novel at about 80%). Sonia appears in chapters 2 and 6, as "Sofia Semionovna" in 4. */
    private class FakeBook : BookTextSource {
        val chapters = (0 until 12).map { i ->
            val filler = "The rain fell on the bridge and the clerk walked home again. ".repeat(480)
            val mention = when (i) {
                2 -> " Sonia came in quietly. "
                4 -> " Sofia Semionovna sat by the window. "
                6 -> " Sonia read to him from the old book. "
                else -> " "
            }
            "Chapter ${i + 1}. " + filler.substring(0, 12_000) + mention + filler.substring(12_000)
        }

        override suspend fun chapterText(bookId: String, spineIndex: Int) = chapters[spineIndex]
        override suspend fun storyStart(bookId: String): FrontMatter.StoryStart? = null
        override suspend fun aliasesFor(bookId: String, name: String, progress: Float) =
            if (name == "Sonia") listOf("Sofia Semionovna") else emptyList()
    }

    private val book = BookEntity(
        id = "b", title = "Crime", author = "F. D.", language = "en", coverPath = null, addedAt = 0, totalChars = 360_000,
    )
    private val fake = FakeBook()
    private val chapterRows = fake.chapters.mapIndexed { i, t -> ChapterEntity("b", i, "c$i.html", "Chapter ${i + 1}", t.length) }
    private val builder = SpoilerFreeContextBuilder(fake)
    private val readerAt = 9 // in chapter 10, with a partial page

    private fun build(focus: String?, saver: Boolean, maxChars: Int = 2_800_000) = runBlocking {
        builder.build(book, chapterRows, readerAt, 0.8f, fake.chapters[readerAt].take(20_000), maxChars, focus = focus, saver = saver)
    }

    @Test
    fun wholeBook_whenNotSaving() {
        val ctx = build("Sonia", saver = false)
        assertFalse(ctx.saver)
        assertNull(ctx.truncationNote)
        assertTrue(ctx.sentChars >= ctx.fullChars)
        assertTrue("whole book ≈ ${ctx.fullChars}", ctx.fullChars > 280_000)
    }

    @Test
    fun aboutASelection_sendsPassagesAndRecentPages() {
        val ctx = build("Sonia", saver = true)
        assertTrue(ctx.saver)
        assertTrue("sent ${ctx.sentChars}", ctx.sentChars <= SpoilerFreeContextBuilder.SAVER_FOCUS_CHARS + 2_000)
        assertTrue("4× fewer at least", ctx.fullChars / ctx.sentChars >= 4)
        // Its first appearance, the passage that uses the full name from the character map, and a later one.
        assertTrue(ctx.priorChapters.contains("Sonia came in quietly"))
        assertTrue(ctx.priorChapters.contains("Sofia Semionovna sat by the window"))
        assertTrue(ctx.priorChapters.contains("Sonia read to him"))
        assertTrue(ctx.priorChapters.contains("also called: Sofia Semionovna"))
        // The recent pages, ending where the reader is: the end of chapter 9, then chapter 10 so far.
        assertTrue(ctx.priorChapters.contains("<chapter n=\"9\""))
        assertFalse(ctx.priorChapters.contains("<chapter n=\"8\""))
        assertTrue(ctx.currentChapter.contains(SpoilerFreeContextBuilder.READER_MARKER))
        assertTrue(ctx.truncationNote!!.startsWith("To use fewer tokens"))
    }

    @Test
    fun openChat_sendsTheRecentPages() {
        val ctx = build(null, saver = true)
        assertTrue(ctx.saver)
        assertTrue("sent ${ctx.sentChars}", ctx.sentChars <= SpoilerFreeContextBuilder.SAVER_CHAT_CHARS + 2_000)
        assertFalse(ctx.priorChapters.contains("<excerpts"))
        assertEquals("To use fewer tokens, the AI read only the recent pages.", ctx.truncationNote)
    }

    /** A short book fits anyway: nothing is trimmed, so there's no "Read the whole book" to offer. */
    @Test
    fun shortBook_isSentWhole() {
        val ctx = runBlocking {
            builder.build(book, chapterRows.take(2), 1, 0.1f, fake.chapters[1].take(5_000), 2_800_000, focus = "Sonia", saver = true)
        }
        assertFalse(ctx.saver)
        assertNull(ctx.truncationNote)
    }

    /**
     * A small local model's window (70K characters) is already about the saver's size: it reads what fits, there's
     * nothing to save, and no "Read the whole book" to offer.
     */
    @Test
    fun smallModel_keepsItsOwnWindow() {
        val ctx = build("Sonia", saver = true, maxChars = 70_000)
        assertFalse(ctx.saver)
        // Its whole window minus the room kept for passages (a quarter), not the saver's ~33K of recent pages.
        assertTrue("sent ${ctx.sentChars}", ctx.sentChars > 50_000)
        assertTrue(ctx.truncationNote!!.startsWith("This book is too long for this AI"))
    }

    @Test
    fun savedAnswers_matchTheSameQuestionAtTheSamePlace() = runBlocking {
        val dir = Files.createTempDirectory("answers").toFile()
        val cache = AnswerCache(dir)
        val key = AnswerCache.key("Sonia", "Who is this and where did they come from?", 0.803f)
        cache.put("b", key, "Sonia is Marmeladov's daughter.")
        assertEquals("Sonia is Marmeladov's daughter.", cache.get("b", AnswerCache.key(" sonia", "who is this, and where did they come from", 0.809f)))
        assertNull(cache.get("b", AnswerCache.key("Sonia", "Who is this and where did they come from?", 0.82f)))
        assertNull(cache.get("other", key))
        assertNotEquals(key, AnswerCache.key("Dunia", "Who is this and where did they come from?", 0.803f))
        repeat(120) { cache.put("b", "k$it", "a$it") }
        assertNull("oldest dropped", cache.get("b", key))
        assertEquals("a119", cache.get("b", "k119"))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun tokenCounts() {
        assertEquals("850", tokenLabel(850))
        assertEquals("4.2K", tokenLabel(4_200))
        assertEquals("95K", tokenLabel(95_300))
        val ctx = build("Sonia", saver = true)
        val tokens = AskController.estimateTokens(ctx, "Sonia", emptyList(), "Who is this?", "An answer.")
        // ~16 pages of recent text plus three short passages: unused room for passages is simply not sent.
        assertTrue("$tokens", tokens in 8_000..17_000)
    }
}
