package com.folio.reader.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.folio.reader.ai.SpoilerFreeContextBuilder
import com.folio.reader.data.db.AppDatabase
import com.folio.reader.data.db.BookEntity
import com.folio.reader.data.db.HighlightEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One-file backups of everything the reader can't get back by re-importing: highlights, notes, bookmarks,
 * progress, collections, stats — plus the books themselves, so a restore brings the library back exactly.
 * Also exports highlights and notes as a readable Markdown file.
 */
class BackupManager(private val context: Context, private val db: AppDatabase, private val books: BookRepository) {

    @Serializable
    data class Manifest(
        val app: String = "Lumen",
        val format: Int = FORMAT,
        val dbVersion: Int,
        val createdAt: Long,
        /** filesDir path of the books when the backup was made; cover paths are stored absolute. */
        val booksRoot: String,
        val books: Int,
        val highlights: Int,
        val bookmarks: Int,
    )

    suspend fun export(out: Uri): Manifest = withContext(Dispatchers.IO) {
        // Move the write-ahead log into the main file so folio.db alone holds everything.
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val manifest = Manifest(
            dbVersion = AppDatabase.VERSION,
            createdAt = System.currentTimeMillis(),
            booksRoot = books.booksRoot.canonicalPath,
            books = db.books().all().size,
            highlights = db.annotations().allHighlights().size,
            bookmarks = db.annotations().bookmarkCount(),
        )
        val stream = context.contentResolver.openOutputStream(out) ?: throw IOException("Couldn't create the file.")
        ZipOutputStream(BufferedOutputStream(stream)).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(json.encodeToString(Manifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(DB_ENTRY))
            context.getDatabasePath(AppDatabase.NAME).inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            books.booksRoot.walkTopDown().filter { it.isFile }.forEach { f ->
                zip.putNextEntry(ZipEntry(BOOKS_PREFIX + f.relativeTo(books.booksRoot).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        manifest
    }

    /** Reads just the manifest (the first entry), to confirm with the reader before restoring. */
    suspend fun inspect(src: Uri): Manifest = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(src) ?: throw IOException("Couldn't open the file.")
        ZipInputStream(BufferedInputStream(stream)).use { zip ->
            val entry = zip.nextEntry
            if (entry?.name != MANIFEST) throw IOException("This file isn't a Lumen backup.")
            val manifest = runCatching { json.decodeFromString(Manifest.serializer(), zip.readBytes().decodeToString()) }
                .getOrElse { throw IOException("The backup is damaged.") }
            if (manifest.format > FORMAT || manifest.dbVersion > AppDatabase.VERSION) {
                throw IOException("This backup comes from a newer version of Lumen. Update the app first.")
            }
            manifest
        }
    }

    /**
     * Replaces the library with the backup's. The current data is moved to `pre-restore/` rather than deleted.
     * Closes the database: the app must restart right after this returns.
     */
    suspend fun restore(src: Uri) = withContext(Dispatchers.IO) {
        val manifest = inspect(src)
        val staging = File(context.filesDir, "restore-staging").apply { deleteRecursively(); mkdirs() }
        try {
            unzip(src, staging)
            val stagedDb = File(staging, DB_ENTRY)
            if (!stagedDb.exists()) throw IOException("The backup doesn't contain the database.")
            val newRoot = books.booksRoot.canonicalPath
            if (manifest.booksRoot != newRoot) {
                SQLiteDatabase.openDatabase(stagedDb.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                    it.execSQL("UPDATE books SET coverPath = REPLACE(coverPath, ?, ?)", arrayOf(manifest.booksRoot, newRoot))
                }
            }

            db.close()
            val dbFile = context.getDatabasePath(AppDatabase.NAME)
            val previous = File(context.filesDir, "pre-restore").apply { deleteRecursively(); mkdirs() }
            for (suffix in listOf("", "-wal", "-shm")) {
                val f = File(dbFile.path + suffix)
                if (f.exists() && !f.renameTo(File(previous, f.name))) f.delete()
            }
            if (books.booksRoot.exists()) books.booksRoot.renameTo(File(previous, "books"))

            if (!stagedDb.renameTo(dbFile)) stagedDb.copyTo(dbFile, overwrite = true)
            val stagedBooks = File(staging, BOOKS_PREFIX.trimEnd('/'))
            if (stagedBooks.exists()) stagedBooks.renameTo(books.booksRoot) else books.booksRoot.mkdirs()
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun unzip(src: Uri, into: File) {
        val root = into.canonicalPath + File.separator
        val stream = context.contentResolver.openInputStream(src) ?: throw IOException("Couldn't open the file.")
        ZipInputStream(BufferedInputStream(stream)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(into, entry.name).canonicalFile
                // Never write outside the staging folder ("zip slip").
                if (!out.path.startsWith(root)) throw IOException("The backup contains invalid paths.")
                if (entry.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }

    /** All highlights and notes as Markdown, grouped by book and chapter. Returns how many were exported. */
    suspend fun exportHighlights(out: Uri): Int = withContext(Dispatchers.IO) {
        val highlights = db.annotations().allHighlights()
        val bookList = db.books().all()
        val chapterLabels = bookList.associate { b ->
            b.id to db.books().chapters(b.id).associate { it.spineIndex to SpoilerFreeContextBuilder.label(it) }
        }
        val md = highlightsMarkdown(bookList, highlights, { bookId, spine -> chapterLabels[bookId]?.get(spine) }, Date())
        val stream = context.contentResolver.openOutputStream(out) ?: throw IOException("Couldn't create the file.")
        stream.bufferedWriter().use { it.write(md) }
        highlights.size
    }

    /** The library a restore replaced is kept for a week in case the reader picked the wrong backup. */
    fun cleanupOldRestoreCopy() {
        val previous = File(context.filesDir, "pre-restore")
        if (previous.exists() && System.currentTimeMillis() - previous.lastModified() > KEEP_PREVIOUS_MS) previous.deleteRecursively()
    }

    companion object {
        const val FORMAT = 1
        private const val KEEP_PREVIOUS_MS = 7L * 24 * 60 * 60 * 1000

        /** "1 book", "3 books". */
        fun count(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
        private const val MANIFEST = "lumen-backup.json"
        private const val DB_ENTRY = "folio.db"
        private const val BOOKS_PREFIX = "books/"
        private val json = Json { ignoreUnknownKeys = true }

        fun backupFileName(now: Date = Date()) =
            "Lumen-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now) + ".zip"

        fun highlightsFileName(now: Date = Date()) =
            "Lumen-highlights-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now) + ".md"

        internal fun highlightsMarkdown(
            books: List<BookEntity>,
            highlights: List<HighlightEntity>,
            chapterLabel: (bookId: String, spineIndex: Int) -> String?,
            now: Date,
        ): String = buildString {
            val byBook = highlights.groupBy { it.bookId }
            val date = SimpleDateFormat("MMMM d, yyyy", Locale.US).format(now)
            appendLine("# My highlights in Lumen")
            appendLine()
            appendLine("Exported on $date · ${count(highlights.size, "highlight", "highlights")} in ${count(byBook.size, "book", "books")}")
            for (book in books) {
                val list = byBook[book.id] ?: continue
                appendLine()
                appendLine("## ${book.title}")
                if (book.author.isNotBlank()) appendLine("*${book.author}*")
                var lastChapter: Int? = null
                for (h in list) {
                    if (h.spineIndex != lastChapter) {
                        lastChapter = h.spineIndex
                        appendLine()
                        appendLine("### ${chapterLabel(book.id, h.spineIndex) ?: "Section ${h.spineIndex + 1}"}")
                    }
                    appendLine()
                    h.text.trim().lines().forEach { appendLine("> $it".trimEnd()) }
                    h.note?.takeIf { it.isNotBlank() }?.let {
                        appendLine()
                        appendLine("**Note:** ${it.trim()}")
                    }
                }
            }
        }
    }
}
