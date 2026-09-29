package com.folio.reader.data.convert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.github.junrar.Archive
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.folio.reader.data.BookRepository
import java.io.File
import java.util.zip.ZipFile

/** Pages of a fixed-layout book (PDF page or comic image) plus the outline entries that point into them. */
data class FixedLayoutBook(
    val title: String,
    val author: String,
    val pageTitles: List<String>,
    val pageTexts: List<String>,
    val pageHrefs: List<String>,
    val toc: List<Triple<String, Int, Int>>, // title, depth, page index
    val coverHref: String?,
)

object PdfImport {
    const val FILE_NAME = "book.pdf"

    /** [pdf] must already be at `<bookDir>/book.pdf`. Extracts per-page text for the AI and renders a cover. */
    fun import(context: Context, pdf: File, fallbackTitle: String): FixedLayoutBook {
        PDFBoxResourceLoader.init(context.applicationContext)
        val doc = try {
            PDDocument.load(pdf)
        } catch (e: InvalidPasswordException) {
            throw UnsupportedBookException("Este PDF está protegido con contraseña.")
        }
        doc.use {
            val pages = doc.numberOfPages
            if (pages == 0) throw UnsupportedBookException("El PDF no tiene páginas")
            val info = doc.documentInformation
            val stripper = PDFTextStripper()
            val texts = (1..pages).map { n ->
                stripper.startPage = n
                stripper.endPage = n
                runCatching { BookRepository.normalizeText(stripper.getText(doc)) }.getOrDefault("")
            }

            val toc = mutableListOf<Triple<String, Int, Int>>()
            fun walk(node: PDOutlineNode, depth: Int) {
                for (item in node.children()) {
                    val page = runCatching { item.findDestinationPage(doc) }.getOrNull()
                    val index = page?.let { doc.pages.indexOf(it) } ?: -1
                    if (index >= 0 && !item.title.isNullOrBlank()) toc += Triple(item.title.trim(), depth, index)
                    walk(item, depth + 1)
                }
            }
            doc.documentCatalog.documentOutline?.let { walk(it, 0) }

            // Page label for the AI context: "<outline section> · p. N".
            val sorted = toc.filter { it.second == 0 }.sortedBy { it.third }
            val titles = (0 until pages).map { i ->
                val section = sorted.lastOrNull { it.third <= i }?.first
                if (section != null) "$section · p. ${i + 1}" else "Página ${i + 1}"
            }
            renderCover(pdf, File(pdf.parentFile, "cover.jpg"))
            return FixedLayoutBook(
                title = info?.title?.trim()?.ifBlank { null } ?: fallbackTitle,
                author = info?.author?.trim().orEmpty(),
                pageTitles = titles,
                pageTexts = texts,
                pageHrefs = List(pages) { FILE_NAME },
                toc = toc,
                coverHref = "cover.jpg".takeIf { File(pdf.parentFile, it).exists() },
            )
        }
    }

    private fun renderCover(pdf: File, out: File) = runCatching {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val renderer = PdfRenderer(pfd)
            try {
                val page = renderer.openPage(0)
                try {
                    val w = 600
                    val h = (w.toFloat() * page.height / page.width).toInt().coerceIn(1, 2400)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    bmp.recycle()
                } finally {
                    page.close()
                }
            } finally {
                renderer.close()
            }
        }
    }
}

object ComicImport {
    /** Extracts page images from a CBZ (zip) or CBR (rar) into `<bookDir>/pages/`. */
    fun import(source: File, kind: SourceKind, bookDir: File, fallbackTitle: String): FixedLayoutBook {
        val pagesDir = File(bookDir, "pages").apply { mkdirs() }
        val extracted = mutableListOf<Pair<String, File>>()
        fun target(name: String, index: Int): File {
            val ext = name.substringAfterLast('.', "jpg").lowercase()
            return File(pagesDir, "%05d.%s".format(index, ext))
        }
        when (kind) {
            SourceKind.CBZ -> ZipFile(source).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && FormatDetector.isImageName(it.name) && !it.name.startsWith("__MACOSX") }
                    .sortedWith(compareBy(FormatDetector.naturalOrder) { it.name })
                    .forEachIndexed { i, e ->
                        val out = target(e.name, i)
                        zip.getInputStream(e).use { src -> out.outputStream().use { src.copyTo(it) } }
                        extracted += e.name to out
                    }
            }
            SourceKind.CBR -> try {
                Archive(source).use { arc ->
                    arc.fileHeaders
                        .filter { !it.isDirectory && FormatDetector.isImageName(it.fileName) }
                        .sortedWith(compareBy(FormatDetector.naturalOrder) { it.fileName })
                        .forEachIndexed { i, h ->
                            val out = target(h.fileName, i)
                            out.outputStream().use { arc.extractFile(h, it) }
                            extracted += h.fileName to out
                        }
                }
            } catch (e: UnsupportedBookException) {
                throw e
            } catch (e: Exception) {
                throw UnsupportedBookException("No se pudo abrir el CBR (los RAR5 aún no se admiten; conviértelo a CBZ).")
            }
            else -> error("not a comic")
        }
        if (extracted.isEmpty()) throw UnsupportedBookException("El cómic no contiene imágenes")
        val hrefs = extracted.map { "pages/" + it.second.name }
        return FixedLayoutBook(
            title = fallbackTitle,
            author = "",
            pageTitles = List(hrefs.size) { "Página ${it + 1}" },
            pageTexts = List(hrefs.size) { "" },
            pageHrefs = hrefs,
            toc = emptyList(),
            coverHref = hrefs.first(),
        )
    }
}
