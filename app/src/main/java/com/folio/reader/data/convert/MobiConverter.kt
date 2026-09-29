package com.folio.reader.data.convert

import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

/**
 * DRM-free Kindle/Mobipocket books: .mobi, .prc, .azw, .azw3.
 *
 * Reads the PalmDB container, decompresses the text records (PalmDOC), and turns the Mobipocket markup into
 * chapters. Dual MOBI+KF8 files use their MOBI6 part; KF8-only (AZW3) files are rebuilt approximately by
 * concatenating each skeleton with its fragments, which keeps text order for kindlegen/Calibre output.
 */
object MobiConverter {

    private const val KF8_BOUNDARY = 121

    fun convert(file: File, fallbackTitle: String): ConvertedBook {
        val data = file.readBytes()
        if (data.size < 86 || String(data, 60, 8, Charsets.ISO_8859_1) !in setOf("BOOKMOBI", "TEXtREAd")) {
            throw UnsupportedBookException("Not a valid MOBI file")
        }
        val pdb = PalmDb(data)
        val rec0 = pdb.record(0)
        val compression = u16(rec0, 0)
        val textRecordCount = u16(rec0, 8)
        val encryption = u16(rec0, 12)
        if (encryption != 0) {
            throw UnsupportedBookException("This book has DRM (copy protection) and can't be opened. Only DRM-free Kindle files are supported.")
        }

        val hasMobiHeader = rec0.size > 20 && String(rec0, 16, 4, Charsets.ISO_8859_1) == "MOBI"
        val headerLen = if (hasMobiHeader) u32(rec0, 20).toInt() else 0
        val encoding = if (hasMobiHeader) u32(rec0, 28).toInt() else 1252
        val version = if (hasMobiHeader) u32(rec0, 36).toInt() else 0
        val firstImage = if (hasMobiHeader && rec0.size >= 112) u32(rec0, 108).toInt() else -1
        val exthFlags = if (hasMobiHeader && rec0.size >= 132) u32(rec0, 128) else 0L
        val extraFlags = if (hasMobiHeader && headerLen >= 0xE4 && rec0.size >= 244) u16(rec0, 242) else 0
        val charset: Charset = if (encoding == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")

        val exth = if (exthFlags and 0x40L != 0L) readExth(rec0, 16 + headerLen) else emptyMap()
        val title = exth[503]?.firstOrNull()?.let { String(it, charset).trim() }
            ?: if (hasMobiHeader) fullName(rec0, charset) else null
        val author = exth[100]?.joinToString(", ") { String(it, charset).trim() }.orEmpty()
        val language = exth[524]?.firstOrNull()?.let { String(it, charset).trim() }

        // Decompress the text of this section (records 1..textRecordCount).
        val raw = ByteArrayOutputStream()
        for (i in 1..textRecordCount) {
            if (i >= pdb.count) break
            var rec = pdb.record(i)
            rec = rec.copyOf(rec.size - trailingSize(rec, extraFlags))
            raw.write(
                when (compression) {
                    1 -> rec
                    2 -> palmDocDecompress(rec)
                    17480 -> throw UnsupportedBookException("This MOBI uses HUFF/CDIC compression, which isn't supported yet. Convert it to EPUB with Calibre.")
                    else -> throw UnsupportedBookException("Unknown MOBI compression ($compression)")
                }
            )
        }
        val html = String(raw.toByteArray(), charset)

        val resources = mutableMapOf<String, ByteArray>()
        fun imageRecord(index: Int): String? {
            if (firstImage < 0) return null
            val recNo = firstImage + index
            if (recNo !in 0 until pdb.count) return null
            val name = "img/r$recNo"
            resources.keys.firstOrNull { it.startsWith("$name.") }?.let { return it }
            val bytes = pdb.record(recNo)
            val ext = ConvertUtil.imageExtension(bytes) ?: return null
            return "$name.$ext".also { resources[it] = bytes }
        }
        val cover = exth[201]?.firstOrNull()?.let { imageRecord(u32(it, 0).toInt()) }

        val isKf8 = version >= 8 && exth[KF8_BOUNDARY] == null
        val chapters = if (isKf8) kf8Chapters(html, ::imageRecord) else mobi6Chapters(html, ::imageRecord)
        if (chapters.isEmpty()) throw UnsupportedBookException("The book has no readable content")
        return ConvertedBook(
            title = title?.ifBlank { null } ?: fallbackTitle,
            author = author,
            language = language,
            chapters = chapters,
            resources = resources,
            coverResource = cover,
        )
    }

    // ---------------------------------------------------------------- markup

    private fun mobi6Chapters(html: String, image: (Int) -> String?): List<ConvertedChapter> {
        val pieces = html.split(Regex("<mbp:pagebreak\\s*/?>", RegexOption.IGNORE_CASE))
        val chapters = pieces.mapNotNull { piece ->
            val doc = Jsoup.parseBodyFragment(piece)
            doc.select("img[recindex]").forEach { img ->
                val src = img.attr("recindex").trim().toIntOrNull()?.let { image(it - 1) }
                if (src != null) { img.attr("src", src); img.removeAttr("recindex") } else img.remove()
            }
            cleanup(doc.body())
            if (doc.body().text().isBlank() && doc.select("img").isEmpty()) null
            else ConvertedChapter(chapterTitle(doc.body()), doc.body().html())
        }
        return if (chapters.size == 1) {
            ConvertUtil.splitByHeadings(Jsoup.parseBodyFragment(chapters[0].bodyHtml), chapters[0].title)
        } else chapters
    }

    private fun kf8Chapters(html: String, image: (Int) -> String?): List<ConvertedChapter> {
        val embed = Regex("kindle:embed:([0-9A-V]{4})", RegexOption.IGNORE_CASE)
        val parts = html.split(Regex("(?=<\\?xml)|(?=<html[\\s>])", RegexOption.IGNORE_CASE)).filter { it.isNotBlank() }
        return parts.mapNotNull { part ->
            val doc = Jsoup.parse(part)
            doc.select("img").forEach { img ->
                val m = embed.find(img.attr("src"))
                val src = m?.let { image(base32(it.groupValues[1]) - 1) }
                if (src != null) img.attr("src", src) else img.remove()
            }
            cleanup(doc.body())
            if (doc.body().text().isBlank() && doc.select("img").isEmpty()) null
            else ConvertedChapter(chapterTitle(doc.body()), doc.body().html())
        }
    }

    private fun cleanup(body: org.jsoup.nodes.Element) {
        body.select("script, style, link, guide").remove()
        // Internal Mobipocket links (filepos=) point at byte offsets we don't keep.
        body.select("a[filepos]").forEach { it.unwrap() }
        body.getAllElements().filter { it.tagName().startsWith("mbp:") }.forEach { it.unwrap() }
    }

    private fun chapterTitle(body: org.jsoup.nodes.Element): String =
        ConvertUtil.firstHeading(body)
            ?: body.select("p, div").firstOrNull { it.text().isNotBlank() }?.text()?.trim()
                ?.takeIf { it.length <= 60 && ConvertUtil.looksLikeHeading(it) }
            ?: ""

    private fun base32(s: String): Int = s.uppercase().fold(0) { acc, c ->
        acc * 32 + if (c.isDigit()) c - '0' else c - 'A' + 10
    }

    // ---------------------------------------------------------------- container

    private class PalmDb(private val data: ByteArray) {
        val count = u16(data, 76)
        private val offsets = IntArray(count) { u32(data, 78 + it * 8).toInt() }

        fun record(i: Int): ByteArray {
            val start = offsets[i]
            val end = if (i + 1 < count) offsets[i + 1] else data.size
            return data.copyOfRange(start, end.coerceAtLeast(start))
        }
    }

    private fun readExth(rec0: ByteArray, start: Int): Map<Int, List<ByteArray>> {
        if (start + 12 > rec0.size || String(rec0, start, 4, Charsets.ISO_8859_1) != "EXTH") return emptyMap()
        val count = u32(rec0, start + 8).toInt()
        val out = mutableMapOf<Int, MutableList<ByteArray>>()
        var p = start + 12
        repeat(count) {
            if (p + 8 > rec0.size) return out
            val type = u32(rec0, p).toInt()
            val len = u32(rec0, p + 4).toInt()
            if (len < 8 || p + len > rec0.size) return out
            out.getOrPut(type) { mutableListOf() } += rec0.copyOfRange(p + 8, p + len)
            p += len
        }
        return out
    }

    private fun fullName(rec0: ByteArray, charset: Charset): String? {
        val off = u32(rec0, 84).toInt()
        val len = u32(rec0, 88).toInt()
        return if (off > 0 && len > 0 && off + len <= rec0.size) String(rec0, off, len, charset) else null
    }

    /** Bytes appended to each text record that aren't text (per the MOBI extra-data flags). */
    internal fun trailingSize(rec: ByteArray, flags: Int): Int {
        var num = 0
        var f = flags shr 1
        while (f != 0) {
            if (f and 1 != 0) num += trailingEntrySize(rec, rec.size - num)
            f = f shr 1
        }
        if (flags and 1 != 0 && rec.size - num - 1 >= 0) num += (rec[rec.size - num - 1].toInt() and 0x3) + 1
        return num.coerceAtMost(rec.size)
    }

    private fun trailingEntrySize(rec: ByteArray, end: Int): Int {
        var bitpos = 0
        var result = 0
        var size = end
        while (size > 0) {
            val v = rec[size - 1].toInt() and 0xFF
            result = result or ((v and 0x7F) shl bitpos)
            bitpos += 7
            size--
            if (v and 0x80 != 0 || bitpos >= 28) break
        }
        return result
    }

    /** PalmDOC LZ77 variant. */
    internal fun palmDocDecompress(src: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(src.size * 2)
        var buf = ByteArray(8192)
        var len = 0
        fun put(b: Int) {
            if (len == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[len++] = b.toByte()
        }
        var i = 0
        while (i < src.size) {
            val c = src[i++].toInt() and 0xFF
            when {
                c in 1..8 -> repeat(c) { if (i < src.size) put(src[i++].toInt()) }
                c < 0x80 -> put(c)
                c >= 0xC0 -> { put(' '.code); put(c xor 0x80) }
                else -> {
                    if (i >= src.size) break
                    val pair = (c shl 8) or (src[i++].toInt() and 0xFF)
                    val dist = (pair shr 3) and 0x7FF
                    val n = (pair and 0x7) + 3
                    if (dist in 1..len) {
                        var from = len - dist
                        repeat(n) { put(buf[from++].toInt()) }
                    }
                }
            }
        }
        out.write(buf, 0, len)
        return out.toByteArray()
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
}
