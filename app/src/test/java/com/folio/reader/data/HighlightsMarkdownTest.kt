package com.folio.reader.data

import com.folio.reader.data.db.BookEntity
import com.folio.reader.data.db.HighlightEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Date

class HighlightsMarkdownTest {

    private fun book(id: String, title: String, author: String = "") =
        BookEntity(id = id, title = title, author = author, language = "en", coverPath = null, addedAt = 0, totalChars = 1)

    private fun hl(bookId: String, spine: Int, start: Int, text: String, note: String? = null) =
        HighlightEntity(bookId = bookId, spineIndex = spine, startOffset = start, endOffset = start + text.length, text = text, color = "yellow", note = note, createdAt = 0)

    @Test
    fun groupsByBookAndChapter_quotesMultilineText_andAddsNotes() {
        val md = BackupManager.highlightsMarkdown(
            books = listOf(book("a", "Crime and Punishment", "Dostoyevsky"), book("b", "Sin subrayados")),
            highlights = listOf(
                hl("a", 0, 10, "First line\nSecond line", note = "  Me gustó  "),
                hl("a", 0, 50, "Same chapter"),
                hl("a", 2, 5, "Later chapter"),
            ),
            chapterLabel = { _, spine -> if (spine == 0) "Part One" else null },
            now = Date(0),
        )
        val expected = """
            ## Crime and Punishment
            *Dostoyevsky*

            ### Part One

            > First line
            > Second line

            **Nota:** Me gustó

            > Same chapter

            ### Sección 3

            > Later chapter
        """.trimIndent()
        assertEquals(expected, md.substringAfter("libro\n\n").trimEnd())
        assertFalse("books without highlights are left out", md.contains("Sin subrayados"))
    }
}
