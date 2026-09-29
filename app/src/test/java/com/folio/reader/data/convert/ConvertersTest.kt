package com.folio.reader.data.convert

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ConvertersTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String, bytes: ByteArray) = tmp.newFile(name).apply { writeBytes(bytes) }

    private fun zip(name: String, entries: Map<String, ByteArray>): File {
        val f = tmp.newFile(name)
        ZipOutputStream(f.outputStream()).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return f
    }

    // ---------------------------------------------------------------- TXT

    @Test
    fun txt_splitsAtChapterHeadings_andReadsGutenbergHeader() {
        val text = """
            Title: Crimen y castigo
            Author: Fiódor Dostoievski

            CAPÍTULO I

            A comienzos de julio, en un tiempo extraordinariamente caluroso,
            un joven salió de la bohardilla.

            Tenía deudas.

            CAPÍTULO II

            Raskólnikov no estaba acostumbrado a la multitud.
        """.trimIndent()
        val book = TxtConverter.convert(file("crimen.txt", text.toByteArray()), "crimen")
        assertEquals("Crimen y castigo", book.title)
        assertEquals("Fiódor Dostoievski", book.author)
        val titles = book.chapters.map { it.title }
        assertTrue(titles.toString(), titles.containsAll(listOf("CAPÍTULO I", "CAPÍTULO II")))
        val ch1 = book.chapters.first { it.title == "CAPÍTULO I" }.bodyHtml
        // Hard-wrapped prose lines are joined into one paragraph.
        assertTrue(ch1, ch1.contains("caluroso, un joven salió"))
    }

    @Test
    fun txt_decodesWindows1252() {
        val bytes = "Capítulo único: el niño".toByteArray(charset("windows-1252"))
        assertEquals("Capítulo único: el niño", TxtConverter.decode(bytes))
    }

    // ---------------------------------------------------------------- RTF

    @Test
    fun rtf_keepsTextAndParagraphs_dropsFormatting() {
        val rtf = "{\\rtf1\\ansi{\\fonttbl{\\f0 Times;}}\\f0\\fs24 Hola \\b mundo\\b0 .\\par Se\\'f1or \\u241?and\\'fa.\\par}"
        val text = RtfConverter.toText(rtf)
        // The space after a control word (\b0 ) is its delimiter, not text.
        assertTrue(text, text.contains("Hola mundo."))
        assertTrue(text, text.contains("Señor ñandú."))
        assertTrue(!text.contains("Times"))
    }

    // ---------------------------------------------------------------- HTML

    @Test
    fun html_splitsByHeadings() {
        val html = "<html><head><title>Mi libro</title><script>x()</script></head><body>" +
            "<h1>Uno</h1><p>Primero.</p><h1>Dos</h1><p>Segundo.</p></body></html>"
        val book = HtmlConverter.convert(file("libro.html", html.toByteArray()), "libro")
        assertEquals("Mi libro", book.title)
        assertEquals(listOf("Uno", "Dos"), book.chapters.map { it.title })
        assertTrue(book.chapters.none { it.bodyHtml.contains("script") })
    }

    // ---------------------------------------------------------------- DOCX

    @Test
    fun docx_paragraphsHeadingsFormattingAndImages() {
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        fun heading(t: String) = "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>$t</w:t></w:r></w:p>"
        val body = heading("Capítulo 1") +
            "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Negrita</w:t></w:r><w:r><w:t xml:space=\"preserve\"> y normal</w:t></w:r></w:p>" +
            "<w:p><w:r><w:drawing><a:blip xmlns:a=\"x\" r:embed=\"rId5\"/></w:drawing></w:r></w:p>" +
            heading("Capítulo 2") + "<w:p><w:r><w:t>Fin.</w:t></w:r></w:p>"
        val docx = zip(
            "doc.docx", mapOf(
                "word/document.xml" to "<w:document xmlns:w=\"$w\" xmlns:r=\"r\"><w:body>$body</w:body></w:document>".toByteArray(),
                "word/_rels/document.xml.rels" to "<Relationships><Relationship Id=\"rId5\" Target=\"media/image1.png\"/></Relationships>".toByteArray(),
                "word/media/image1.png" to byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()),
                "docProps/core.xml" to "<cp:coreProperties xmlns:dc=\"dc\"><dc:title>Mi novela</dc:title><dc:creator>Ana</dc:creator></cp:coreProperties>".toByteArray(),
            )
        )
        assertEquals(SourceKind.DOCX, FormatDetector.detect(docx, "doc.docx"))
        val book = DocxConverter.convert(docx, "doc")
        assertEquals("Mi novela", book.title)
        assertEquals("Ana", book.author)
        assertEquals(listOf("Capítulo 1", "Capítulo 2"), book.chapters.map { it.title })
        val ch1 = book.chapters[0].bodyHtml
        assertTrue(ch1, ch1.contains("<strong>Negrita</strong> y normal"))
        assertTrue(ch1, ch1.contains("<img src=\"img/image1.png\""))
        assertTrue("img/image1.png" in book.resources)
    }

    // ---------------------------------------------------------------- FB2

    @Test
    fun fb2_metadataSectionsPoemsAndImages() {
        val png = Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2))
        val fb2 = """<?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
              <description><title-info>
                <author><first-name>Lev</first-name><last-name>Tolstói</last-name></author>
                <book-title>Guerra y paz</book-title><lang>es</lang>
                <coverpage><image l:href="#cover.png"/></coverpage>
              </title-info></description>
              <body>
                <section><title><p>Primera parte</p></title><p>Pues bien, <emphasis>príncipe</emphasis>.</p>
                  <poem><stanza><v>Verso uno</v><v>Verso dos</v></stanza></poem></section>
                <section><title><p>Segunda parte</p></title><p>Otra cosa.</p></section>
              </body>
              <binary id="cover.png" content-type="image/png">$png</binary>
            </FictionBook>"""
        val f = file("gyp.fb2", fb2.toByteArray())
        assertEquals(SourceKind.FB2, FormatDetector.detect(f, "gyp.fb2"))
        val book = Fb2Converter.convert(f, "gyp")
        assertEquals("Guerra y paz", book.title)
        assertEquals("Lev Tolstói", book.author)
        assertEquals("es", book.language)
        assertEquals("img/cover.png", book.coverResource)
        assertEquals(listOf("Primera parte", "Segunda parte"), book.chapters.map { it.title })
        val ch1 = book.chapters[0].bodyHtml
        assertTrue(ch1, ch1.contains("<em>príncipe</em>"))
        assertTrue(ch1, ch1.contains("Verso uno<br>Verso dos<br>"))
    }

    // ---------------------------------------------------------------- MOBI

    @Test
    fun palmDoc_literalsBackReferencesAndSpaces() {
        // "abc" + copy(dist 3, len 6) + (space + 'a')
        val src = byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 0x80.toByte(), 0x1B, 0xE1.toByte())
        assertEquals("abcabcabc a", String(MobiConverter.palmDocDecompress(src)))
        // 1..8 = copy that many raw bytes
        assertArrayEquals(byteArrayOf(0xC3.toByte(), 0xA9.toByte()), MobiConverter.palmDocDecompress(byteArrayOf(2, 0xC3.toByte(), 0xA9.toByte())))
    }

    @Test
    fun trailingEntries_multibyteFlag() {
        // Flag bit 0: last byte's low 2 bits + 1 = bytes to strip.
        val rec = "hola".toByteArray() + byteArrayOf(0x41, 0x01)
        assertEquals(2, MobiConverter.trailingSize(rec, 1))
    }

    @Test
    fun mobi_uncompressedBookWithPagebreaksAndExth() {
        val html = "<html><body><h2>Capítulo 1</h2><p>Érase una vez.</p><mbp:pagebreak/><h2>Capítulo 2</h2><p>Y colorín.</p></body></html>"
        val f = file("cuento.mobi", buildMobi(html.toByteArray(Charsets.UTF_8), title = "Cuento", author = "Anónimo"))
        assertEquals(SourceKind.MOBI, FormatDetector.detect(f, "cuento.mobi"))
        val book = MobiConverter.convert(f, "cuento")
        assertEquals("Cuento", book.title)
        assertEquals("Anónimo", book.author)
        assertEquals(listOf("Capítulo 1", "Capítulo 2"), book.chapters.map { it.title })
        assertTrue(book.chapters[0].bodyHtml.contains("Érase una vez."))
    }

    @Test(expected = UnsupportedBookException::class)
    fun mobi_withDrm_isRejected() {
        MobiConverter.convert(file("drm.azw", buildMobi("x".toByteArray(), "t", "a", encryption = 2)), "drm")
    }

    /** Minimal PalmDB/MOBI: record 0 (PalmDOC + MOBI + EXTH) and one uncompressed UTF-8 text record. */
    private fun buildMobi(text: ByteArray, title: String, author: String, encryption: Int = 0): ByteArray {
        fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
        fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        fun exthRec(type: Int, data: ByteArray) = be32(type) + be32(data.size + 8) + data
        val exthRecords = exthRec(503, title.toByteArray()) + exthRec(100, author.toByteArray())
        val exth = "EXTH".toByteArray() + be32(12 + exthRecords.size) + be32(2) + exthRecords

        val mobiLen = 0xE8
        val mobi = ByteArray(mobiLen).also { m ->
            "MOBI".toByteArray().copyInto(m, 0)
            be32(mobiLen).copyInto(m, 4)
            be32(2).copyInto(m, 8)            // type: book
            be32(65001).copyInto(m, 12)       // UTF-8
            be32(6).copyInto(m, 20)           // version
            be32(-1).copyInto(m, 0x5C)        // first image: none
            be32(0x40).copyInto(m, 0x70)      // EXTH present
        }
        val palmDoc = be16(1) + be16(0) + be32(text.size) + be16(1) + be16(4096) + be16(encryption) + be16(0)
        val rec0 = palmDoc + mobi + exth
        val records = listOf(rec0, text)

        val headerSize = 78 + records.size * 8 + 2
        val out = ByteArrayOutputStream()
        val name = ByteArray(32).also { "cuento".toByteArray().copyInto(it) }
        out.write(name)
        out.write(ByteArray(28))                          // attributes..sortInfo
        out.write("BOOKMOBI".toByteArray())               // type + creator at 60
        out.write(ByteArray(8))                           // uniqueIdSeed, nextRecordList
        out.write(be16(records.size))                     // at 76
        var offset = headerSize
        records.forEachIndexed { i, r -> out.write(be32(offset)); out.write(be32(i)); offset += r.size }
        out.write(ByteArray(2))
        records.forEach { out.write(it) }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- detection & comics

    @Test
    fun detect_cbzAndPdfAndText() {
        val cbz = zip("c.cbz", mapOf("p2.jpg" to byteArrayOf(1), "p10.jpg" to byteArrayOf(1), "p1.jpg" to byteArrayOf(1)))
        assertEquals(SourceKind.CBZ, FormatDetector.detect(cbz, "c.cbz"))
        assertEquals(SourceKind.PDF, FormatDetector.detect(file("a.pdf", "%PDF-1.7\n".toByteArray()), "a.pdf"))
        assertEquals(SourceKind.TXT, FormatDetector.detect(file("notas", "Hola, esto es texto.\n".toByteArray()), "notas"))
        assertEquals(listOf("p1.jpg", "p2.jpg", "p10.jpg"), listOf("p10.jpg", "p2.jpg", "p1.jpg").sortedWith(FormatDetector.naturalOrder))
    }

    @Test
    fun writer_producesHtmlChaptersAndToc() {
        val root = tmp.newFolder("book")
        val parsed = ConvertedBookWriter.write(
            ConvertedBook("T", "A", null, listOf(ConvertedChapter("Uno", "<p>a&nbsp;b</p>"), ConvertedChapter("", "<p>c</p>")),
                mapOf("img/x.png" to byteArrayOf(1)), "img/x.png"),
            root,
        )
        assertEquals(listOf("content/0000.html", "content/0001.html"), parsed.spine.map { it.href })
        assertEquals(listOf("Uno"), parsed.toc.map { it.title })
        assertEquals("content/img/x.png", parsed.coverHref)
        assertTrue(File(root, "content/0000.html").readText().contains("<title>Uno</title>"))
    }
}
