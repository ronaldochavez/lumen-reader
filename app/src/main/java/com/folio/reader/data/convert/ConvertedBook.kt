package com.folio.reader.data.convert

import com.folio.reader.data.epub.ParsedEpub
import com.folio.reader.data.epub.SpineItem
import com.folio.reader.data.epub.TocItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import java.io.File

/**
 * A non-EPUB book turned into chapters of HTML. Written to disk as plain HTML files so the reflowable reader
 * (and highlights, AI context, character map) treat it exactly like an EPUB.
 */
data class ConvertedBook(
    val title: String,
    val author: String,
    val language: String? = null,
    val chapters: List<ConvertedChapter>,
    /** Binary resources keyed by path relative to the content dir, e.g. "img/cover.jpg". */
    val resources: Map<String, ByteArray> = emptyMap(),
    val coverResource: String? = null,
)

data class ConvertedChapter(val title: String, val bodyHtml: String)

class UnsupportedBookException(message: String) : Exception(message)

object ConvertedBookWriter {
    private const val CONTENT_DIR = "content"

    fun write(book: ConvertedBook, root: File): ParsedEpub {
        require(book.chapters.isNotEmpty()) { "El archivo no tiene contenido legible" }
        val dir = File(root, CONTENT_DIR).apply { mkdirs() }
        book.resources.forEach { (path, bytes) ->
            val f = File(dir, path)
            if (!f.canonicalPath.startsWith(dir.canonicalPath)) return@forEach
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
        }
        val spine = mutableListOf<SpineItem>()
        val toc = mutableListOf<TocItem>()
        book.chapters.forEachIndexed { i, ch ->
            val name = "%04d.html".format(i)
            File(dir, name).writeText(page(ch))
            val href = "$CONTENT_DIR/$name"
            spine += SpineItem(href, "text/html")
            if (ch.title.isNotBlank()) toc += TocItem(ch.title, href, null, 0)
        }
        return ParsedEpub(
            title = book.title.ifBlank { "Sin título" },
            author = book.author.ifBlank { "Autor desconocido" },
            language = book.language,
            coverHref = book.coverResource?.let { "$CONTENT_DIR/$it" }?.takeIf { File(root, it).exists() },
            spine = spine,
            toc = toc,
        )
    }

    private fun page(ch: ConvertedChapter): String {
        val doc = Jsoup.parse("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title></title></head><body></body></html>")
        doc.title(ch.title)
        doc.body().html(ch.bodyHtml)
        doc.select("script, iframe, object, embed").remove()
        doc.outputSettings().charset("UTF-8").escapeMode(Entities.EscapeMode.base).prettyPrint(false)
        return doc.outerHtml()
    }
}

/** Shared helpers for converters. */
internal object ConvertUtil {

    fun escape(text: String): String = Entities.escape(text)

    /** Splits a long single-document body at <h1>/<h2> headings; returns one chapter if there are none. */
    fun splitByHeadings(doc: Document, fallbackTitle: String): List<ConvertedChapter> {
        val body = doc.body()
        val level = listOf("h1", "h2").firstOrNull { body.select(it).size >= 2 }
            ?: return listOf(ConvertedChapter(firstHeading(body) ?: fallbackTitle, body.html()))
        val chapters = mutableListOf<ConvertedChapter>()
        var title = ""
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) chapters += ConvertedChapter(title.ifBlank { fallbackTitle }, current.toString())
            current.clear()
        }
        // Walk top-level blocks; if headings are nested in wrappers, flatten one level.
        val blocks = body.children().flatMap { if (it.tagName() in setOf("div", "section", "article") && it.select(level).isNotEmpty()) it.children() else listOf(it) }
        for (el in blocks) {
            if (el.tagName() == level) {
                flush()
                title = el.text().trim()
            }
            current.append(el.outerHtml())
        }
        flush()
        return chapters.ifEmpty { listOf(ConvertedChapter(fallbackTitle, body.html())) }
    }

    fun firstHeading(el: Element): String? =
        el.selectFirst("h1, h2, h3")?.text()?.trim()?.takeIf { it.isNotBlank() && it.length < 120 }

    /** Detects common image formats from magic bytes. */
    fun imageExtension(bytes: ByteArray): String? = when {
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() -> "png"
        bytes.size > 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() -> "gif"
        bytes.size > 12 && String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
        bytes.size > 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "bmp"
        else -> null
    }

    /** "Chapter 3", "Capítulo IV", "PARTE UNO", "Prólogo", "XII", "# Title"… */
    private val HEADING = Regex(
        "^(#{1,3}\\s+.+|(cap[ií]tulo|chapter|parte|part|libro|book|pr[oó]logo|prologue|ep[ií]logo|epilogue|introducci[oó]n|introduction)\\b.{0,80}|[IVXLCDM]{1,7}\\.?|\\d{1,3}\\.?)$",
        RegexOption.IGNORE_CASE,
    )

    fun looksLikeHeading(line: String): Boolean {
        val t = line.trim()
        return t.length in 1..90 && HEADING.matches(t)
    }

    /** Groups plain-text paragraphs into chapters at heading lines, or into ~20k-char parts if there are none. */
    fun textToChapters(paragraphs: List<String>, fallbackTitle: String): List<ConvertedChapter> {
        val headingIdx = paragraphs.indices.filter { looksLikeHeading(paragraphs[it]) }
        val chapters = mutableListOf<ConvertedChapter>()
        if (headingIdx.size >= 2) {
            if (headingIdx.first() > 0) chapters += chapter("Inicio", paragraphs.subList(0, headingIdx.first()), heading = null)
            headingIdx.forEachIndexed { k, start ->
                val end = headingIdx.getOrNull(k + 1) ?: paragraphs.size
                val title = paragraphs[start].trim().removePrefix("#").removePrefix("#").removePrefix("#").trim()
                chapters += chapter(title, paragraphs.subList(start + 1, end), heading = title)
            }
        } else {
            var part = 1
            var acc = mutableListOf<String>()
            var size = 0
            for (p in paragraphs) {
                acc += p; size += p.length
                if (size > 20_000) {
                    chapters += chapter("Parte ${part++}", acc, heading = null)
                    acc = mutableListOf(); size = 0
                }
            }
            if (acc.isNotEmpty()) chapters += chapter(if (part == 1) fallbackTitle else "Parte $part", acc, heading = null)
        }
        return chapters.filter { it.bodyHtml.isNotBlank() }
    }

    private fun chapter(title: String, paragraphs: List<String>, heading: String?): ConvertedChapter {
        val sb = StringBuilder()
        if (heading != null) sb.append("<h2>").append(escape(heading)).append("</h2>")
        for (p in paragraphs) {
            if (p.isBlank()) continue
            sb.append("<p>").append(p.trim().lines().joinToString("<br>") { escape(it.trim()) }).append("</p>")
        }
        return ConvertedChapter(title, sb.toString())
    }
}
