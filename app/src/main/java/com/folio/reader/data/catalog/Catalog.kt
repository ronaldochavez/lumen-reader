package com.folio.reader.data.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Free, public-domain books from two open catalogs:
 * - Project Gutenberg, searched through Gutendex (https://gutendex.com), a free JSON API over Gutenberg's catalog.
 * - Standard Ebooks, a volunteer project that turns public-domain texts into carefully formatted editions. Searched
 *   through the results page its OpenSearch description publishes (https://standardebooks.org/opensearch), which is
 *   XHTML with schema.org markup.
 */
enum class CatalogSource(val label: String) {
    STANDARD_EBOOKS("Standard Ebooks"),
    GUTENBERG("Project Gutenberg"),
}

data class CatalogBook(
    val source: CatalogSource,
    /** Gutenberg number, or the Standard Ebooks path ("/ebooks/author/title/translator"). */
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    /** ISO 639-1 code, if the listing says. */
    val language: String?,
    /** Gutenberg listings link the EPUB directly; Standard Ebooks needs its book page ([StandardEbooksClient.details]). */
    val epubUrl: String?,
    val pageUrl: String,
    val subjects: List<String> = emptyList(),
    val downloads: Int? = null,
) {
    val key get() = "${source.name}:$id"
}

data class CatalogPage(val books: List<CatalogBook>, val hasMore: Boolean)

data class CatalogDetails(val epubUrl: String, val summary: String?, val wordCount: Int?)

class GutendexClient(private val base: String = "https://gutendex.com") {

    /** Most downloaded first; with a blank [query] that's simply the most popular books. */
    fun search(query: String, languages: List<String>, page: Int): CatalogPage {
        val params = buildList {
            if (query.isNotBlank()) add("search=" + CatalogHttp.encode(AuthorSpellings.gutenberg(query)))
            if (languages.isNotEmpty()) add("languages=" + languages.joinToString(","))
            add("mime_type=" + CatalogHttp.encode("application/epub"))
            if (page > 1) add("page=$page")
        }
        return parse(CatalogHttp.getText("$base/books/?" + params.joinToString("&")))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        internal fun parse(body: String): CatalogPage {
            val response = json.decodeFromString<GxResponse>(body)
            val books = response.results.mapNotNull { b ->
                val epub = b.formats["application/epub+zip"] ?: return@mapNotNull null
                CatalogBook(
                    source = CatalogSource.GUTENBERG,
                    id = b.id.toString(),
                    title = b.title.replace(Regex("\\s+"), " ").trim(),
                    author = b.authors.joinToString(" & ") { personName(it.name) }.ifBlank { "Unknown author" },
                    coverUrl = b.formats["image/jpeg"],
                    language = b.languages.firstOrNull(),
                    epubUrl = epub,
                    pageUrl = "https://www.gutenberg.org/ebooks/${b.id}",
                    subjects = (b.bookshelves.map { it.removePrefix("Category: ") } + b.subjects.map { it.substringBefore(" -- ") })
                        .filterNot { it.contains("Best Books Ever") || it.contains("Harvard Classics") }
                        .distinct()
                        .take(4),
                    downloads = b.downloadCount,
                )
            }
            return CatalogPage(books, hasMore = response.next != null)
        }

        /** Gutenberg lists people as "Last, First[, title]": "Cervantes Saavedra, Miguel de" → "Miguel de Cervantes Saavedra". */
        internal fun personName(raw: String): String {
            val parts = raw.replace(Regex("\\s*\\([^)]*\\)"), "").split(", ")
            return if (parts.size >= 2) "${parts[1]} ${parts[0]}".trim() else raw.trim()
        }
    }
}

@Serializable
private data class GxResponse(val next: String? = null, val results: List<GxBook> = emptyList())

@Serializable
private data class GxBook(
    val id: Int,
    val title: String = "",
    val authors: List<GxPerson> = emptyList(),
    val languages: List<String> = emptyList(),
    val subjects: List<String> = emptyList(),
    val bookshelves: List<String> = emptyList(),
    val formats: Map<String, String> = emptyMap(),
    @SerialName("download_count") val downloadCount: Int = 0,
)

@Serializable
private data class GxPerson(val name: String)

class StandardEbooksClient(private val base: String = "https://standardebooks.org") {

    /** With a blank [query] this is the whole catalog, newest releases first. All Standard Ebooks are in English. */
    fun search(query: String, page: Int): CatalogPage {
        val params = buildList {
            if (query.isNotBlank()) add("query=" + CatalogHttp.encode(AuthorSpellings.standardEbooks(query)))
            add("per-page=$PER_PAGE")
            if (page > 1) add("page=$page")
        }
        val url = "$base/ebooks?" + params.joinToString("&")
        return parseList(CatalogHttp.getText(url, accept = "application/xhtml+xml"), url)
    }

    /** The book page has the download links and a one-line, spoiler-free abstract. */
    fun details(book: CatalogBook): CatalogDetails {
        val html = CatalogHttp.getText(book.pageUrl, accept = "application/xhtml+xml")
        return parseDetails(html, book.pageUrl) ?: throw IOException("Standard Ebooks has no EPUB for this book")
    }

    companion object {
        private const val PER_PAGE = 24

        internal fun parseList(html: String, pageUrl: String): CatalogPage {
            val doc = Jsoup.parse(html, pageUrl)
            val books = doc.select("li[typeof=schema:Book]").mapNotNull { li ->
                val path = li.attr("about").ifBlank { return@mapNotNull null }
                val title = li.selectFirst("p:not(.author) [property=schema:name]")?.text()?.trim()
                    ?: return@mapNotNull null
                CatalogBook(
                    source = CatalogSource.STANDARD_EBOOKS,
                    id = path,
                    title = title,
                    author = li.select("p.author [property=schema:name]").joinToString(" & ") { it.text().trim() }
                        .ifBlank { "Unknown author" },
                    coverUrl = li.selectFirst("img[property=schema:image]")?.absUrl("src")?.ifBlank { null },
                    language = "en",
                    epubUrl = null,
                    pageUrl = li.absUrl("about").ifBlank { "https://standardebooks.org$path" },
                )
            }
            return CatalogPage(books, hasMore = doc.selectFirst("nav.pagination a[rel=next]") != null)
        }

        internal fun parseDetails(html: String, pageUrl: String): CatalogDetails? {
            val doc = Jsoup.parse(html, pageUrl)
            val epubs = doc.select("a[property=schema:contentUrl]").map { it.absUrl("href") }
                .filter { it.substringBefore('?').endsWith(".epub") && !it.contains(".kepub.") }
            // The "compatible" EPUB works everywhere; the "advanced" one is EPUB 3 with newer CSS.
            val epub = epubs.firstOrNull { !it.contains("_advanced.") } ?: epubs.firstOrNull() ?: return null
            return CatalogDetails(
                epubUrl = epub,
                summary = doc.selectFirst("meta[property=schema:abstract]")?.attr("content")?.trim()?.ifBlank { null },
                wordCount = doc.selectFirst("meta[property=schema:wordCount]")?.attr("content")?.toIntOrNull(),
            )
        }
    }
}

/**
 * Both catalogs match words exactly and spell each author one way, not the same way: Gutenberg has "Dostoyevsky",
 * Standard Ebooks "Dostoevsky", and Spanish readers type "Dostoievski". Covers the most-searched transliterated names.
 */
internal object AuthorSpellings {
    private class Author(val gutenberg: String, val standardEbooks: String, vararg val variants: String)

    private val authors = listOf(
        Author("dostoyevsky", "dostoevsky", "dostoevski", "dostoievski", "dostoievsky", "dostoyevski", "dostoiewski"),
        Author("tolstoy", "tolstoy", "tolstoi"),
        Author("chekhov", "chekhov", "chejov", "chekov", "tchekhov"),
        Author("turgenev", "turgenev", "turgueniev", "turguenev", "turgeniev"),
        Author("pushkin", "pushkin", "puskin", "pouchkine"),
    )
    private val byWord = authors.flatMap { a -> (a.variants.toList() + a.gutenberg + a.standardEbooks).map { it to a } }.toMap()

    fun gutenberg(query: String) = respell(query) { it.gutenberg }
    fun standardEbooks(query: String) = respell(query) { it.standardEbooks }

    private fun respell(query: String, spelling: (Author) -> String): String =
        query.trim().split(Regex("\\s+")).joinToString(" ") { word ->
            val plain = Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            byWord[plain]?.let(spelling) ?: word
        }
}

internal object CatalogHttp {
    private const val USER_AGENT = "Lumen e-reader for Android (+https://github.com/ronaldochavez/lumen-reader)"
    private val REFRESH_URL = Regex("(?i)url\\s*=\\s*(.+)")

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun getText(url: String, accept: String? = null): String {
        val conn = open(url, accept)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("${URL(url).host} answered HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun getBytes(url: String): ByteArray {
        val conn = open(url, null)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Downloads an EPUB to [dest]. A site may answer a download link with a "your download has started" page that
     * starts the real download with a meta refresh (Standard Ebooks does, to ask for donations): that page is
     * followed the way a browser would, keeping its cookies.
     */
    fun downloadEpub(url: String, dest: File, onProgress: (Float?) -> Unit) {
        var target = url
        var cookies: String? = null
        repeat(3) {
            val conn = open(target, "application/epub+zip,application/octet-stream;q=0.9,*/*;q=0.5")
            cookies?.let { conn.setRequestProperty("Cookie", it) }
            try {
                val code = conn.responseCode
                if (code == 429) throw IOException("Too many downloads right now. Try again in a few minutes.")
                if (code !in 200..299) throw IOException("${URL(target).host} answered HTTP $code")
                if (conn.contentType.orEmpty().contains("html")) {
                    val page = conn.inputStream.bufferedReader().use { it.readText() }
                    cookies = conn.headerFields["Set-Cookie"]?.joinToString("; ") { it.substringBefore(';') } ?: cookies
                    target = followLink(page, conn.url.toString()) ?: throw IOException("The site didn't send a book file")
                    return@repeat
                }
                val total = conn.contentLengthLong.takeIf { it > 0 }
                onProgress(if (total != null) 0f else null)
                conn.inputStream.use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total != null) onProgress((done.toFloat() / total).coerceAtMost(1f))
                        }
                    }
                }
                val magic = dest.inputStream().use { ByteArray(2).also { b -> it.read(b) } }
                if (magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()) throw IOException("The download isn't an EPUB")
                return
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("The site didn't send a book file")
    }

    /** Where an intermediate download page sends the browser: its meta refresh, else its first EPUB link. */
    internal fun followLink(html: String, pageUrl: String): String? {
        val doc = Jsoup.parse(html, pageUrl)
        doc.select("meta[http-equiv~=(?i)refresh]").forEach { meta ->
            val target = REFRESH_URL.find(meta.attr("content"))?.groupValues?.get(1)?.trim()?.trim('\'', '"')
            if (!target.isNullOrEmpty()) return URL(URL(pageUrl), target).toString()
        }
        return doc.select("a[href]").map { it.absUrl("href") }.firstOrNull { it.substringBefore('?').endsWith(".epub") }
    }

    private fun open(url: String, accept: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            accept?.let { setRequestProperty("Accept", it) }
        }
}
