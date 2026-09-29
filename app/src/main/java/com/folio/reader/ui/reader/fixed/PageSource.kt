package com.folio.reader.ui.reader.fixed

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/** Renders page [index] of a fixed-layout book as a bitmap roughly [targetWidth] px wide. */
interface PageSource : Closeable {
    suspend fun render(index: Int, targetWidth: Int): Bitmap?
}

private class BitmapCache : LruCache<String, Bitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

class PdfPageSource(file: File) : PageSource {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    // PdfRenderer allows only one open page at a time.
    private val lock = Mutex()
    private val cache = BitmapCache()

    override suspend fun render(index: Int, targetWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$index@$targetWidth"
        cache.get(key)?.let { return@withContext it }
        lock.withLock {
            if (index !in 0 until renderer.pageCount) return@withLock null
            val page = renderer.openPage(index)
            try {
                val w = targetWidth.coerceIn(200, 2400)
                val h = (w.toFloat() * page.height / page.width).toInt().coerceIn(1, 4000)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cache.put(key, it)
                }
            } finally {
                page.close()
            }
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }
}

class ImagePageSource(private val root: File, private val hrefs: List<String>) : PageSource {
    private val cache = BitmapCache()

    override suspend fun render(index: Int, targetWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$index@$targetWidth"
        cache.get(key)?.let { return@withContext it }
        val file = File(root, hrefs.getOrNull(index) ?: return@withContext null)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.also { cache.put(key, it) }
    }

    override fun close() = Unit
}
