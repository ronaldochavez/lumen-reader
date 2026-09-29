package com.folio.reader.data.convert

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipFile

/** Word documents (.docx). Paragraphs, bold/italic, line breaks, headings (chapter splits) and images. */
object DocxConverter {

    fun convert(file: File, fallbackTitle: String): ConvertedBook = ZipFile(file).use { zip ->
        fun read(name: String) = zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }

        val docXml = read("word/document.xml") ?: throw UnsupportedBookException("DOCX inválido")
        val doc = Jsoup.parse(docXml, "", Parser.xmlParser())
        val rels = read("word/_rels/document.xml.rels")?.let { Jsoup.parse(it, "", Parser.xmlParser()) }
        val relTargets = rels?.getElementsByTag("Relationship")?.associate { it.attr("Id") to it.attr("Target") }.orEmpty()
        val core = read("docProps/core.xml")?.let { Jsoup.parse(it, "", Parser.xmlParser()) }
        val title = core?.getElementsByTag("dc:title")?.text()?.trim().orEmpty().ifBlank { fallbackTitle }
        val author = core?.getElementsByTag("dc:creator")?.text()?.trim().orEmpty()

        val resources = mutableMapOf<String, ByteArray>()
        fun image(relId: String): String? {
            val target = relTargets[relId] ?: return null
            val entry = zip.getEntry("word/" + target.removePrefix("/word/").removePrefix("./")) ?: return null
            val name = "img/" + target.substringAfterLast('/')
            if (name !in resources) resources[name] = zip.getInputStream(entry).use { it.readBytes() }
            return name
        }

        data class Para(val html: String, val headingLevel: Int?)

        fun runsToHtml(p: Element): String {
            val sb = StringBuilder()
            for (r in p.getElementsByTag("w:r")) {
                val rPr = r.getElementsByTag("w:rPr").firstOrNull()
                val bold = rPr?.getElementsByTag("w:b")?.firstOrNull()?.attr("w:val")?.let { it != "0" && it != "false" } ?: false
                val italic = rPr?.getElementsByTag("w:i")?.firstOrNull()?.attr("w:val")?.let { it != "0" && it != "false" } ?: false
                val piece = StringBuilder()
                for (c in r.children()) {
                    when (c.tagName()) {
                        // wholeText keeps leading/trailing spaces (xml:space="preserve") that text() would trim.
                        "w:t" -> piece.append(ConvertUtil.escape(c.wholeText()))
                        "w:tab" -> piece.append(' ')
                        "w:br", "w:cr" -> piece.append("<br>")
                        "w:drawing", "w:pict" -> c.allElements
                            .firstNotNullOfOrNull { it.attributes().firstOrNull { a -> a.key == "r:embed" || a.key == "r:id" }?.value }
                            ?.let(::image)?.let { piece.append("<img src=\"$it\" alt=\"\">") }
                    }
                }
                var s = piece.toString()
                if (italic) s = "<em>$s</em>"
                if (bold) s = "<strong>$s</strong>"
                sb.append(s)
            }
            return sb.toString()
        }

        val headingStyle = Regex("(?i)^(heading|t[ií]tulo|berschrift|titre)\\s*(\\d)?$|^(?i)title$")
        val paras = doc.getElementsByTag("w:p").map { p ->
            val style = p.getElementsByTag("w:pStyle").firstOrNull()?.attr("w:val").orEmpty()
            val m = headingStyle.find(style)
            val level = when {
                m == null -> null
                style.equals("title", true) -> 1
                else -> m.groupValues[2].toIntOrNull() ?: 1
            }
            Para(runsToHtml(p), level)
        }.filter { it.html.isNotBlank() }

        val splitLevel = listOf(1, 2).firstOrNull { lvl -> paras.count { it.headingLevel == lvl } >= 2 }
        val chapters = mutableListOf<ConvertedChapter>()
        var chTitle = ""
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotBlank()) chapters += ConvertedChapter(chTitle.ifBlank { if (chapters.isEmpty()) title else "" }, sb.toString())
            sb.clear()
        }
        for (p in paras) {
            if (splitLevel != null && p.headingLevel == splitLevel) {
                flush()
                chTitle = Jsoup.parse(p.html).text()
            }
            if (p.headingLevel != null) sb.append("<h${(p.headingLevel + 1).coerceIn(2, 4)}>").append(p.html).append("</h${(p.headingLevel + 1).coerceIn(2, 4)}>")
            else sb.append("<p>").append(p.html).append("</p>")
        }
        flush()
        if (chapters.isEmpty()) throw UnsupportedBookException("El documento está vacío")
        ConvertedBook(title, author, null, chapters, resources)
    }
}
