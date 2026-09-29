package com.folio.reader.data.convert

import java.util.Base64
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** FictionBook 2 (.fb2, and zipped .fb2.zip / .fbz). */
object Fb2Converter {

    fun convert(file: File, fallbackTitle: String): ConvertedBook {
        val isZip = file.inputStream().use { it.read() == 'P'.code && it.read() == 'K'.code }
        return if (isZip) {
            ZipFile(file).use { zip ->
                val entry = zip.entries().asSequence().firstOrNull { it.name.endsWith(".fb2", ignoreCase = true) }
                    ?: throw UnsupportedBookException("The ZIP doesn't contain an .fb2 file")
                zip.getInputStream(entry).use { parse(it, fallbackTitle) }
            }
        } else {
            file.inputStream().use { parse(it, fallbackTitle) }
        }
    }

    private fun parse(input: InputStream, fallbackTitle: String): ConvertedBook {
        // charset = null lets Jsoup honour the XML declaration (FB2 files are often windows-1251).
        val doc = Jsoup.parse(input, null, "", Parser.xmlParser())
        fun Element.local() = tagName().substringAfter(':')
        fun Element.child(name: String) = children().firstOrNull { it.local() == name }
        fun Element.hrefId() = (attributes().firstOrNull { it.key.endsWith("href") }?.value ?: "").removePrefix("#")

        val titleInfo = doc.allElements.firstOrNull { it.local() == "title-info" }
        val title = titleInfo?.child("book-title")?.text()?.trim().orEmpty().ifBlank { fallbackTitle }
        val author = titleInfo?.children()?.filter { it.local() == "author" }?.joinToString(", ") { a ->
            listOf("first-name", "middle-name", "last-name").mapNotNull { a.child(it)?.text()?.trim()?.ifBlank { null } }
                .joinToString(" ").ifBlank { a.child("nickname")?.text().orEmpty() }
        }.orEmpty()
        val lang = titleInfo?.child("lang")?.text()?.trim()?.ifBlank { null }

        val resources = mutableMapOf<String, ByteArray>()
        doc.allElements.filter { it.local() == "binary" }.forEach { bin ->
            val id = bin.attr("id").ifBlank { return@forEach }
            val bytes = runCatching { Base64.getMimeDecoder().decode(bin.text().trim()) }.getOrNull() ?: return@forEach
            resources["img/${safeName(id)}"] = bytes
        }
        val cover = titleInfo?.child("coverpage")?.children()?.firstOrNull { it.local() == "image" }?.hrefId()
            ?.let { "img/${safeName(it)}" }?.takeIf { it in resources }

        fun render(el: Element, sb: StringBuilder, depth: Int) {
            for (node: Node in el.childNodes()) {
                if (node is TextNode) { sb.append(ConvertUtil.escape(node.text())); continue }
                if (node !is Element) continue
                when (node.local()) {
                    "section" -> render(node, sb, depth + 1)
                    "title" -> {
                        val h = "h${(depth + 1).coerceIn(2, 4)}"
                        sb.append("<$h>").append(node.children().joinToString("<br>") { ConvertUtil.escape(it.text()) }.ifBlank { ConvertUtil.escape(node.text()) }).append("</$h>")
                    }
                    "subtitle" -> { sb.append("<h4>"); render(node, sb, depth); sb.append("</h4>") }
                    "p" -> { sb.append("<p>"); render(node, sb, depth); sb.append("</p>") }
                    "emphasis" -> { sb.append("<em>"); render(node, sb, depth); sb.append("</em>") }
                    "strong" -> { sb.append("<strong>"); render(node, sb, depth); sb.append("</strong>") }
                    "strikethrough" -> { sb.append("<s>"); render(node, sb, depth); sb.append("</s>") }
                    "sub", "sup", "code" -> { sb.append("<${node.local()}>"); render(node, sb, depth); sb.append("</${node.local()}>") }
                    "empty-line" -> sb.append("<br>")
                    "poem", "stanza" -> { sb.append("<div style=\"margin:1em 0 1em 1.5em\">"); render(node, sb, depth); sb.append("</div>") }
                    "v" -> { render(node, sb, depth); sb.append("<br>") }
                    "epigraph", "cite", "annotation" -> { sb.append("<blockquote>"); render(node, sb, depth); sb.append("</blockquote>") }
                    "text-author" -> { sb.append("<p><em>"); render(node, sb, depth); sb.append("</em></p>") }
                    "image" -> {
                        val path = "img/${safeName(node.hrefId())}"
                        if (path in resources) sb.append("<img src=\"$path\" alt=\"\">")
                    }
                    "a" -> render(node, sb, depth)
                    "table", "tr", "td", "th" -> { sb.append("<${node.local()}>"); render(node, sb, depth); sb.append("</${node.local()}>") }
                    else -> render(node, sb, depth)
                }
            }
        }

        val bodies = doc.allElements.filter { it.local() == "body" }
        val chapters = mutableListOf<ConvertedChapter>()
        for (body in bodies) {
            val isNotes = body.attr("name").equals("notes", true) || body.attr("name").equals("comments", true)
            val sections = body.children().filter { it.local() == "section" }
            if (isNotes) {
                val sb = StringBuilder("<h2>Notas</h2>")
                render(body, sb, 1)
                chapters += ConvertedChapter("Notas", sb.toString())
                continue
            }
            // Book-level title/epigraph before the first section.
            val intro = StringBuilder()
            body.children().filter { it.local() != "section" }.forEach { el ->
                val wrapper = Element("div").appendChild(el.clone())
                render(wrapper, intro, 0)
            }
            if (intro.isNotBlank()) chapters += ConvertedChapter(title, intro.toString())
            for (sec in sections) {
                val sb = StringBuilder()
                render(Element("div").appendChild(sec.clone()), sb, 0)
                val secTitle = sec.children().firstOrNull { it.local() == "title" }?.text()?.trim().orEmpty()
                chapters += ConvertedChapter(secTitle, sb.toString())
            }
        }
        if (chapters.isEmpty()) throw UnsupportedBookException("The FB2 has no content")
        return ConvertedBook(title, author, lang, chapters, resources, cover)
    }

    private fun safeName(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
