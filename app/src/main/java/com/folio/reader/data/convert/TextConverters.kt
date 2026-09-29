package com.folio.reader.data.convert

import org.jsoup.Jsoup
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Plain text (.txt, .md). Detects chapters from heading lines; reads Project Gutenberg headers. */
object TxtConverter {
    fun convert(file: File, fallbackTitle: String): ConvertedBook = fromText(decode(file.readBytes()), fallbackTitle)

    fun fromText(raw: String, fallbackTitle: String): ConvertedBook {
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val head = text.lineSequence().take(80).toList()
        val title = head.firstNotNullOfOrNull { Regex("^Title:\\s*(.+)$").find(it.trim())?.groupValues?.get(1) } ?: fallbackTitle
        val author = head.firstNotNullOfOrNull { Regex("^Author:\\s*(.+)$").find(it.trim())?.groupValues?.get(1) } ?: ""

        // Paragraphs are separated by blank lines when the file uses them; otherwise each line is a paragraph.
        val blankSeparated = Regex("\\n\\s*\\n").findAll(text).count()
        val lineCount = text.count { it == '\n' } + 1
        val paragraphs = if (blankSeparated > lineCount / 12) {
            text.split(Regex("\\n\\s*\\n")).map { block ->
                // Headings stay on their own; hard-wrapped prose lines are rejoined.
                val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.size > 1 && lines.all { it.length < 60 }) lines.joinToString("\n") else lines.joinToString(" ")
            }
        } else {
            text.lines()
        }.filter { it.isNotBlank() }

        val chapters = ConvertUtil.textToChapters(paragraphs, title)
        if (chapters.isEmpty()) throw UnsupportedBookException("The text file is empty")
        return ConvertedBook(title = title, author = author, chapters = chapters)
    }

    /** UTF-8 if valid, otherwise Windows-1252 (the usual legacy encoding for Spanish/English texts). */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }
}

/** Rich Text Format: a small tokenizer that keeps the text and paragraph breaks and drops formatting. */
object RtfConverter {
    fun convert(file: File, fallbackTitle: String): ConvertedBook {
        val rtf = String(file.readBytes(), Charsets.ISO_8859_1)
        if (!rtf.startsWith("{\\rtf")) throw UnsupportedBookException("The RTF file isn't valid")
        return TxtConverter.fromText(toText(rtf).replace(Regex("\\n(?=\\S)"), "\n\n"), fallbackTitle)
    }

    private val SKIP_DESTINATIONS = setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "themedata", "datastore", "xmlnstbl", "listtable", "listoverridetable", "rsidtbl", "generator")

    fun toText(rtf: String): String {
        val out = StringBuilder()
        val cp1252 = Charset.forName("windows-1252")
        var i = 0
        var depth = 0
        var skipDepth = -1
        var ucSkip = 1
        while (i < rtf.length) {
            val ch = rtf[i]
            when {
                ch == '{' -> { depth++; i++ }
                ch == '}' -> { if (depth == skipDepth) skipDepth = -1; depth--; i++ }
                ch == '\\' -> {
                    val next = rtf.getOrNull(i + 1) ?: break
                    when {
                        next == '\'' -> {
                            val hex = rtf.substring(i + 2, minOf(i + 4, rtf.length))
                            if (skipDepth < 0) hex.toIntOrNull(16)?.let { out.append(String(byteArrayOf(it.toByte()), cp1252)) }
                            i += 4
                        }
                        next == '*' -> { if (skipDepth < 0) skipDepth = depth; i += 2 }
                        next == '\\' || next == '{' || next == '}' -> { if (skipDepth < 0) out.append(next); i += 2 }
                        next.isLetter() -> {
                            var j = i + 1
                            while (j < rtf.length && rtf[j].isLetter()) j++
                            val word = rtf.substring(i + 1, j)
                            var k = j
                            if (k < rtf.length && (rtf[k] == '-' || rtf[k].isDigit())) {
                                k++
                                while (k < rtf.length && rtf[k].isDigit()) k++
                            }
                            val param = rtf.substring(j, k).toIntOrNull()
                            if (k < rtf.length && rtf[k] == ' ') k++
                            i = k
                            if (word in SKIP_DESTINATIONS && skipDepth < 0) skipDepth = depth
                            if (skipDepth >= 0) continue
                            when (word) {
                                "par", "line", "sect", "page" -> out.append('\n')
                                "tab" -> out.append(' ')
                                "uc" -> ucSkip = param ?: 1
                                "u" -> if (param != null) {
                                    out.append((if (param < 0) param + 65536 else param).toChar())
                                    // Skip the ANSI fallback characters that follow \uN.
                                    var skip = ucSkip
                                    while (skip > 0 && i < rtf.length) {
                                        if (rtf[i] == '\\' && rtf.getOrNull(i + 1) == '\'') i += 4 else i++
                                        skip--
                                    }
                                }
                                "emdash" -> out.append('—')
                                "endash" -> out.append('–')
                                "lquote" -> out.append('‘')
                                "rquote" -> out.append('’')
                                "ldblquote" -> out.append('“')
                                "rdblquote" -> out.append('”')
                            }
                        }
                        else -> i += 2
                    }
                }
                ch == '\r' || ch == '\n' -> i++
                else -> { if (skipDepth < 0) out.append(ch); i++ }
            }
        }
        return out.toString()
    }
}

/** A single HTML file. Split into chapters at h1/h2 headings. */
object HtmlConverter {
    fun convert(file: File, fallbackTitle: String): ConvertedBook {
        val doc = Jsoup.parse(file, null)
        doc.select("script, style, link, iframe, nav, header, footer, form").remove()
        // Only embedded images survive; relative files weren't imported with the page.
        doc.select("img").forEach { if (!it.attr("src").startsWith("data:")) it.remove() }
        val title = doc.title().ifBlank { ConvertUtil.firstHeading(doc.body()) ?: fallbackTitle }
        val author = doc.selectFirst("meta[name=author]")?.attr("content").orEmpty()
        val lang = doc.selectFirst("html")?.attr("lang")?.ifBlank { null }
        return ConvertedBook(title, author, lang, ConvertUtil.splitByHeadings(doc, title))
    }
}
