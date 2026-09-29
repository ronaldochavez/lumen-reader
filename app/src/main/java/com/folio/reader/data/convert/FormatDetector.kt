package com.folio.reader.data.convert

import java.io.File
import java.util.zip.ZipFile

enum class SourceKind { EPUB, PDF, MOBI, FB2, DOCX, TXT, RTF, HTML, CBZ, CBR }

/** Identifies a file by its magic bytes first and its extension second. */
object FormatDetector {

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

    fun isImageName(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

    fun detect(file: File, fileName: String): SourceKind {
        val head = ByteArray(2048).let { buf ->
            val n = file.inputStream().use { it.read(buf) }.coerceAtLeast(0)
            buf.copyOf(n)
        }
        val ascii = String(head, Charsets.ISO_8859_1)
        val ext = fileName.lowercase().removeSuffix(".zip").substringAfterLast('.', "")

        return when {
            ascii.startsWith("%PDF") -> SourceKind.PDF
            head.size >= 68 && ascii.substring(60, 68) in setOf("BOOKMOBI", "TEXtREAd") -> SourceKind.MOBI
            ascii.startsWith("Rar!") -> SourceKind.CBR
            ascii.startsWith("{\\rtf") -> SourceKind.RTF
            ascii.startsWith("PK") -> detectZip(file, ext)
            ascii.startsWith("AT&TFORM") -> throw UnsupportedBookException("DjVu isn't supported yet. Convert it to PDF.")
            ascii.startsWith("ITSF") -> throw UnsupportedBookException("CHM isn't supported. Convert it to EPUB with Calibre.")
            ascii.startsWith("ITOLITLS") -> throw UnsupportedBookException("LIT isn't supported. Convert it to EPUB with Calibre.")
            ascii.startsWith("êDRMION") || ascii.startsWith("CONT") && ext in setOf("kfx", "azw", "azw8") ->
                throw UnsupportedBookException("Kindle KFX format: it usually has DRM and can't be opened. If it's yours and DRM-free, convert it to EPUB with Calibre.")
            ascii.contains("<FictionBook") -> SourceKind.FB2
            Regex("<!doctype html|<html[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(ascii) || ext in setOf("html", "htm", "xhtml") -> SourceKind.HTML
            ext in setOf("txt", "text", "md", "markdown") || looksLikeText(head) -> SourceKind.TXT
            else -> throw UnsupportedBookException("Unrecognized format (.$ext).")
        }
    }

    private fun detectZip(file: File, ext: String): SourceKind = ZipFile(file).use { zip ->
        val names = zip.entries().asSequence().map { it.name }.toList()
        val mimetype = zip.getEntry("mimetype")?.let { e -> zip.getInputStream(e).use { String(it.readBytes()).trim() } }
        when {
            mimetype == "application/epub+zip" || "META-INF/container.xml" in names -> SourceKind.EPUB
            "word/document.xml" in names -> SourceKind.DOCX
            names.any { it.endsWith(".fb2", ignoreCase = true) } -> SourceKind.FB2
            names.count { isImageName(it) } > 0 && names.count { isImageName(it) } >= names.count { !it.endsWith("/") } / 2 -> SourceKind.CBZ
            ext == "epub" -> SourceKind.EPUB
            else -> throw UnsupportedBookException("This ZIP doesn't contain a recognizable book.")
        }
    }

    /** Heuristic: no NUL bytes and mostly printable → plain text. */
    private fun looksLikeText(head: ByteArray): Boolean {
        if (head.isEmpty()) return false
        if (head.any { it == 0.toByte() }) return false
        val printable = head.count { val b = it.toInt() and 0xFF; b >= 0x20 || b == 0x09 || b == 0x0A || b == 0x0D }
        return printable > head.size * 0.95
    }

    /** Sorts "page2.jpg" before "page10.jpg". */
    val naturalOrder: Comparator<String> = Comparator { a, b ->
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val cmp = if (x[0].isDigit() && y[0].isDigit()) (x.toBigInteger().compareTo(y.toBigInteger())) else x.compareTo(y)
            if (cmp != 0) return@Comparator cmp
        }
        ra.size - rb.size
    }
}
