package com.folio.reader.data.catalog

import com.folio.reader.ui.catalog.CatalogViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing real responses saved from Gutendex and standardebooks.org (test/resources/catalog). */
class CatalogParsingTest {

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResource("catalog/$name")!!.readText()

    @Test
    fun gutendex_search() {
        val page = GutendexClient.parse(fixture("gutendex_crime_punishment.json"))
        assertEquals(3, page.books.size)
        assertFalse(page.hasMore)
        val book = page.books.first()
        assertEquals(CatalogSource.GUTENBERG, book.source)
        assertEquals("2554", book.id)
        assertEquals("Crime and Punishment", book.title)
        assertEquals("Fyodor Dostoyevsky", book.author)
        assertEquals("en", book.language)
        assertEquals("https://www.gutenberg.org/ebooks/2554.epub3.images", book.epubUrl)
        assertEquals("https://www.gutenberg.org/cache/epub/2554/pg2554.cover.medium.jpg", book.coverUrl)
        assertTrue(book.subjects.toString(), "Russian Literature" in book.subjects)
    }

    @Test
    fun gutenberg_personNames() {
        assertEquals("Miguel de Cervantes Saavedra", GutendexClient.personName("Cervantes Saavedra, Miguel de"))
        assertEquals("Leo Tolstoy", GutendexClient.personName("Tolstoy, Leo, graf"))
        assertEquals("H. G. Wells", GutendexClient.personName("Wells, H. G. (Herbert George)"))
        assertEquals("Homer", GutendexClient.personName("Homer"))
    }

    @Test
    fun authorSpellings_matchEachCatalog() {
        assertEquals("crimen dostoyevsky", AuthorSpellings.gutenberg("crimen  Dostoievski"))
        assertEquals("dostoevsky", AuthorSpellings.standardEbooks("Dostoyevsky"))
        assertEquals("dostoevsky", AuthorSpellings.standardEbooks("Dostoievski"))
        assertEquals("tolstoy", AuthorSpellings.gutenberg("Tolstói"))
        assertEquals("Anna Karenina", AuthorSpellings.gutenberg(" Anna Karenina "))
    }

    @Test
    fun standardEbooks_search() {
        val url = "https://standardebooks.org/ebooks?query=dostoevsky&per-page=24"
        val page = StandardEbooksClient.parseList(fixture("se_search_dostoevsky.xhtml"), url)
        assertEquals(9, page.books.size)
        assertFalse(page.hasMore)
        val book = page.books.first()
        assertEquals("The House of the Dead", book.title)
        assertEquals("Fyodor Dostoevsky", book.author)
        assertEquals("https://standardebooks.org/ebooks/fyodor-dostoevsky/the-house-of-the-dead/constance-garnett", book.pageUrl)
        assertTrue(book.coverUrl!!, book.coverUrl!!.startsWith("https://standardebooks.org/images/covers/"))
        assertNull(book.epubUrl)
        assertTrue(page.books.any { it.title == "Demons" })
    }

    @Test
    fun standardEbooks_browseHasMorePages() {
        val page = StandardEbooksClient.parseList(fixture("se_browse_page2.xhtml"), "https://standardebooks.org/ebooks?page=2")
        assertEquals(12, page.books.size)
        assertTrue(page.hasMore)
        assertTrue(page.books.all { it.title.isNotBlank() && it.author.isNotBlank() })
    }

    @Test
    fun standardEbooks_bookPage() {
        val url = "https://standardebooks.org/ebooks/fyodor-dostoevsky/demons/constance-garnett"
        val d = StandardEbooksClient.parseDetails(fixture("se_book_demons.xhtml"), url)!!
        assertEquals("$url/downloads/fyodor-dostoevsky_demons_constance-garnett.epub", d.epubUrl)
        assertEquals("A small town becomes swept up in the fervor of attempted revolution.", d.summary)
        assertEquals(253903, d.wordCount)
    }

    /** Standard Ebooks answers download links with a thank-you page that starts the download with a meta refresh. */
    @Test
    fun downloadPage_followsMetaRefresh() {
        val page = """<html><head><meta http-equiv="refresh" content="0; url=/ebooks/a/b/downloads/a_b.epub?source=download" /></head>
            <body><h1>Your Download Has Started!</h1></body></html>"""
        assertEquals(
            "https://standardebooks.org/ebooks/a/b/downloads/a_b.epub?source=download",
            CatalogHttp.followLink(page, "https://standardebooks.org/ebooks/a/b/downloads/a_b.epub"),
        )
        val linkOnly = """<html><body><a href="/donate">Donate</a> <a href="files/book.epub">Download</a></body></html>"""
        assertEquals("https://example.org/x/files/book.epub", CatalogHttp.followLink(linkOnly, "https://example.org/x/page"))
        assertNull(CatalogHttp.followLink("<html><body>Nothing</body></html>", "https://example.org/"))
    }

    @Test
    fun alreadyInLibrary_matching() {
        assertEquals("frankenstein", CatalogViewModel.normalizeTitle("Frankenstein; Or, The Modern Prometheus"))
        assertEquals(CatalogViewModel.normalizeTitle("The Idiot"), CatalogViewModel.normalizeTitle("Idiot"))
        assertTrue(CatalogViewModel.sameAuthor("Fyodor Dostoevsky", "Fyodor Dostoyevsky"))
        assertFalse(CatalogViewModel.sameAuthor("Mary Shelley", "Fyodor Dostoyevsky"))
    }
}
