package com.folio.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.folio.reader.data.convert.ComicImport
import com.folio.reader.data.convert.ConvertedBook
import com.folio.reader.data.convert.ConvertedBookWriter
import com.folio.reader.data.convert.DocxConverter
import com.folio.reader.data.convert.Fb2Converter
import com.folio.reader.data.convert.FixedLayoutBook
import com.folio.reader.data.convert.FormatDetector
import com.folio.reader.data.convert.HtmlConverter
import com.folio.reader.data.convert.MobiConverter
import com.folio.reader.data.convert.PdfImport
import com.folio.reader.data.convert.RtfConverter
import com.folio.reader.data.convert.SourceKind
import com.folio.reader.data.convert.TxtConverter
import com.folio.reader.data.db.AppDatabase
import com.folio.reader.data.db.BookEntity
import com.folio.reader.data.db.BookFormat
import com.folio.reader.data.db.ChapterEntity
import com.folio.reader.data.db.TocEntity
import com.folio.reader.data.epub.EpubParser
import com.folio.reader.data.epub.ParsedEpub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class BookRepository(private val context: Context, private val db: AppDatabase) {

    val booksRoot: File = File(context.filesDir, "books").apply { mkdirs() }

    fun observeBooks() = db.books().observeAll()

    suspend fun get(id: String) = db.books().get(id)
    suspend fun chapters(id: String) = db.books().chapters(id)
    suspend fun toc(id: String) = db.books().toc(id)
    suspend fun markOpened(id: String) = db.books().markOpened(id, System.currentTimeMillis())

    suspend fun updateProgress(id: String, spineIndex: Int, offset: Int, fraction: Float, progress: Float) =
        db.books().updateProgress(id, spineIndex, offset, fraction, progress, System.currentTimeMillis())

    fun bookDir(bookId: String) = File(booksRoot, bookId)

    /** Plain text of a spine item (or PDF page), extracted at import time. Used to build the spoiler-free AI context. */
    suspend fun chapterText(bookId: String, spineIndex: Int): String = withContext(Dispatchers.IO) {
        val f = File(bookDir(bookId), "$TEXT_DIR/$spineIndex.txt")
        if (f.exists()) f.readText() else ""
    }

    suspend fun delete(bookId: String) = withContext(Dispatchers.IO) {
        db.books().delete(bookId)
        bookDir(bookId).deleteRecursively()
    }

    /**
     * Copies, converts and indexes a book in any supported format. Returns the new book id.
     * Reflowable formats become HTML chapters (same pipeline as EPUB); PDFs and comics keep their pages.
     */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dir = bookDir(id).apply { mkdirs() }
        val fileName = displayName(uri)
        val baseTitle = fileName.substringBeforeLast('.').removeSuffix(".fb2").replace('_', ' ').trim().ifBlank { "Sin título" }
        val tmp = File(context.cacheDir, "import-$id")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("No se pudo abrir el archivo")
            input.use { src -> tmp.outputStream().use { src.copyTo(it) } }

            when (val kind = FormatDetector.detect(tmp, fileName)) {
                SourceKind.EPUB -> {
                    unzip(tmp, dir)
                    indexReflowable(id, dir, EpubParser(dir).parse())
                }
                SourceKind.PDF -> {
                    val pdf = File(dir, PdfImport.FILE_NAME)
                    tmp.copyTo(pdf, overwrite = true)
                    indexFixedLayout(id, dir, BookFormat.PDF, PdfImport.import(context, pdf, baseTitle))
                }
                SourceKind.CBZ, SourceKind.CBR ->
                    indexFixedLayout(id, dir, BookFormat.COMIC, ComicImport.import(tmp, kind, dir, baseTitle))
                else -> {
                    val converted: ConvertedBook = when (kind) {
                        SourceKind.MOBI -> MobiConverter.convert(tmp, baseTitle)
                        SourceKind.FB2 -> Fb2Converter.convert(tmp, baseTitle)
                        SourceKind.DOCX -> DocxConverter.convert(tmp, baseTitle)
                        SourceKind.RTF -> RtfConverter.convert(tmp, baseTitle)
                        SourceKind.HTML -> HtmlConverter.convert(tmp, baseTitle)
                        else -> TxtConverter.convert(tmp, baseTitle)
                    }
                    indexReflowable(id, dir, ConvertedBookWriter.write(converted, dir))
                }
            }
            id
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        } finally {
            tmp.delete()
        }
    }

    private suspend fun indexReflowable(id: String, dir: File, parsed: ParsedEpub) {
        val textDir = File(dir, TEXT_DIR).apply { mkdirs() }
        val titlesByHref = LinkedHashMap<String, String>()
        parsed.toc.forEach { if (it.title.isNotBlank()) titlesByHref.putIfAbsent(it.href, it.title) }

        val chapters = parsed.spine.mapIndexed { index, item ->
            val text = extractText(File(dir, item.href))
            File(textDir, "$index.txt").writeText(text)
            ChapterEntity(bookId = id, spineIndex = index, href = item.href, title = titlesByHref[item.href].orEmpty(), charCount = text.length)
        }
        val spineByHref = parsed.spine.withIndex().associate { it.value.href to it.index }
        val toc = parsed.toc.mapIndexedNotNull { ord, t ->
            val spine = spineByHref[t.href] ?: return@mapIndexedNotNull null
            TocEntity(bookId = id, ord = ord, title = t.title.ifBlank { "Sección ${spine + 1}" }, depth = t.depth, spineIndex = spine, fragment = t.fragment)
        }
        save(
            BookEntity(
                id = id,
                title = parsed.title,
                author = parsed.author,
                language = parsed.language,
                coverPath = parsed.coverHref?.let { File(dir, it).absolutePath },
                addedAt = System.currentTimeMillis(),
                totalChars = chapters.sumOf { it.charCount.toLong() },
                format = BookFormat.REFLOW.id,
            ),
            chapters, toc,
        )
    }

    private suspend fun indexFixedLayout(id: String, dir: File, format: BookFormat, book: FixedLayoutBook) {
        val textDir = File(dir, TEXT_DIR).apply { mkdirs() }
        val chapters = book.pageHrefs.indices.map { i ->
            val text = book.pageTexts[i]
            if (text.isNotEmpty()) File(textDir, "$i.txt").writeText(text)
            ChapterEntity(bookId = id, spineIndex = i, href = book.pageHrefs[i], title = book.pageTitles[i], charCount = text.length)
        }
        val toc = book.toc.mapIndexed { ord, (title, depth, page) ->
            TocEntity(bookId = id, ord = ord, title = title, depth = depth, spineIndex = page, fragment = null)
        }
        save(
            BookEntity(
                id = id,
                title = book.title,
                author = book.author.ifBlank { if (format == BookFormat.COMIC) "Cómic" else "Autor desconocido" },
                language = null,
                coverPath = book.coverHref?.let { File(dir, it).absolutePath },
                addedAt = System.currentTimeMillis(),
                totalChars = chapters.sumOf { it.charCount.toLong() },
                format = format.id,
            ),
            chapters, toc,
        )
    }

    private suspend fun save(book: BookEntity, chapters: List<ChapterEntity>, toc: List<TocEntity>) = db.withTransaction {
        db.books().insert(book)
        db.books().insertChapters(chapters)
        if (toc.isNotEmpty()) db.books().insertToc(toc)
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "libro"

    private fun unzip(zip: File, dest: File) {
        val destPath = dest.canonicalPath + File.separator
        ZipFile(zip).use { zf ->
            for (entry in zf.entries()) {
                val out = File(dest, entry.name)
                // Zip-slip guard.
                if (!out.canonicalPath.startsWith(destPath)) continue
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { src -> out.outputStream().use { src.copyTo(it) } }
                }
            }
        }
    }

    private fun extractText(file: File): String {
        if (!file.exists()) return ""
        val body = runCatching { Jsoup.parse(file, "UTF-8").body() }.getOrNull() ?: return ""
        return normalizeText(body.wholeText())
    }

    companion object {
        const val TEXT_DIR = ".folio/text"

        fun normalizeText(raw: String): String = raw
            .replace("\r", "")
            .replace(Regex("[ \\t\\u00A0]+"), " ")
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
