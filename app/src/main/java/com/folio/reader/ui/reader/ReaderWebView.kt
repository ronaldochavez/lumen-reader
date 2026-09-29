package com.folio.reader.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.nodes.Entities
import java.io.ByteArrayInputStream
import java.io.File

const val READER_HOST = "appassets.androidplatform.net"
const val BOOK_BASE_URL = "https://$READER_HOST/book/"

fun chapterUrl(bookId: String, href: String): String =
    BOOK_BASE_URL + bookId + "/" + href.split('/').joinToString("/") { Uri.encode(it) }

@Serializable
data class SelectionInfo(val text: String, val start: Int, val end: Int)

enum class SelectionAction(val title: String) {
    ASK("Ask AI"),
    DEFINE("Define"),
    HIGHLIGHT("Highlight"),
    NOTE("Note"),
    COPY("Copy"),
}

sealed interface BridgeEvent {
    data object Ready : BridgeEvent
    /** [anchor]: the reading position within the page (survives relayouts); [start]/[end]: the visible text. */
    data class Page(val page: Int, val pageCount: Int, val start: Int, val end: Int, val total: Int, val snippet: String, val anchor: Int) : BridgeEvent
    data class Boundary(val forward: Boolean) : BridgeEvent
    data object ToggleChrome : BridgeEvent
    data class HighlightTap(val id: Long) : BridgeEvent
    data class Link(val url: String) : BridgeEvent
}

/** JS -> Kotlin. Methods run on a WebView background thread, so everything is posted to the main thread. */
class ReaderBridge(private val onEvent: (BridgeEvent) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private fun post(e: BridgeEvent) = main.post { onEvent(e) }

    @JavascriptInterface fun onReady() = post(BridgeEvent.Ready)
    @JavascriptInterface fun onPage(page: Int, pageCount: Int, start: Int, end: Int, total: Int, snippet: String, anchor: Int) =
        post(BridgeEvent.Page(page, pageCount, start, end, total, snippet, anchor))
    @JavascriptInterface fun onBoundary(direction: String) = post(BridgeEvent.Boundary(direction == "next"))
    @JavascriptInterface fun onToggleChrome() = post(BridgeEvent.ToggleChrome)
    @JavascriptInterface fun onHighlightTap(id: String) { id.toLongOrNull()?.let { post(BridgeEvent.HighlightTap(it)) } }
    @JavascriptInterface fun onLink(url: String) = post(BridgeEvent.Link(url))
}

/**
 * WebView that replaces the text-selection toolbar with Folio's actions (Ask AI, highlight, note, copy).
 */
@SuppressLint("ViewConstructor")
class FolioWebView(context: Context) : WebView(context) {

    var onSelectionAction: ((SelectionAction, SelectionInfo) -> Unit)? = null

    /** Rotation / split-screen: make sure the page re-measures itself even if WebView skips the resize event. */
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (ow != 0 && oh != 0 && (w != ow || h != oh)) {
            postDelayed({ evaluateJavascript("window.dispatchEvent(new Event('resize'))", null) }, 150)
        }
    }

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(wrap(callback), type)

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        super.startActionMode(wrap(callback))

    private fun wrap(original: ActionMode.Callback): ActionMode.Callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            original.onCreateActionMode(mode, menu)
            populate(menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            original.onPrepareActionMode(mode, menu)
            populate(menu)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val action = SelectionAction.entries.getOrNull(item.itemId - MENU_BASE) ?: return original.onActionItemClicked(mode, item)
            // Read text + offsets before finishing the mode, which clears the selection.
            evaluateJavascript("Folio.selectionInfo()") { raw ->
                val info = runCatching { selectionJson.decodeFromString<SelectionInfo?>(raw ?: "null") }.getOrNull()
                mode.finish()
                if (info != null) onSelectionAction?.invoke(action, info)
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = original.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (original is ActionMode.Callback2) original.onGetContentRect(mode, view, outRect)
            else super.onGetContentRect(mode, view, outRect)
        }
    }

    private fun populate(menu: Menu) {
        menu.clear()
        SelectionAction.entries.forEachIndexed { i, a ->
            menu.add(Menu.NONE, MENU_BASE + i, i, a.title).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
    }

    private companion object {
        const val MENU_BASE = 0x7F01
        val selectionJson = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Serves extracted book files at https://appassets.androidplatform.net/book/<bookId>/<path>, injecting the
 * reader stylesheet and script into every (X)HTML document.
 */
class BookPathHandler(private val booksRoot: File) : WebViewAssetLoader.PathHandler {
    private val rootPath = booksRoot.canonicalPath + File.separator

    override fun handle(path: String): WebResourceResponse? {
        val file = File(booksRoot, path)
        if (!file.canonicalPath.startsWith(rootPath) || !file.isFile) return notFound()
        val ext = file.extension.lowercase()
        val mime = when (ext) {
            "xhtml", "xht" -> "application/xhtml+xml"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "svg" -> "image/svg+xml"
            "otf" -> "font/otf"
            "ttf" -> "font/ttf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
        if (ext in setOf("xhtml", "xht", "html", "htm")) {
            var html = inject(file.readText(Charsets.UTF_8))
            if (mime == "application/xhtml+xml") html = numericEntities(html)
            return WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
        }
        return WebResourceResponse(mime, null, file.inputStream())
    }

    private fun inject(html: String): String {
        val tags = """<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no"/>""" +
            """<link rel="stylesheet" type="text/css" href="https://$READER_HOST/assets/reader/reader.css"/>""" +
            """<script type="text/javascript" src="https://$READER_HOST/assets/reader/reader.js"></script>"""
        HEAD_CLOSE.find(html)?.let { return html.replaceRange(it.range.first, it.range.first, tags) }
        HTML_OPEN.find(html)?.let { return html.replaceRange(it.range.last + 1, it.range.last + 1, "<head>$tags</head>") }
        return tags + html
    }

    /**
     * XHTML is parsed as strict XML, where HTML named entities like &nbsp; are undefined and abort the whole
     * page. Many EPUBs use them anyway, so rewrite them as numeric character references.
     */
    private fun numericEntities(xml: String): String = NAMED_ENTITY.replace(xml) { m ->
        val name = m.groupValues[1]
        if (name in XML_ENTITIES) return@replace m.value
        val chars = Entities.getByName(name)
        if (chars.isEmpty()) m.value else chars.codePoints().toArray().joinToString("") { "&#$it;" }
    }

    private fun notFound() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private companion object {
        val HEAD_CLOSE = Regex("</head\\s*>", RegexOption.IGNORE_CASE)
        val HTML_OPEN = Regex("<html[^>]*>", RegexOption.IGNORE_CASE)
        val NAMED_ENTITY = Regex("&([A-Za-z][A-Za-z0-9]{1,31});")
        val XML_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")
    }
}

/** Routes appassets requests to the loader and blocks everything else (EPUBs shouldn't phone home). */
class ReaderWebViewClient(private val loader: WebViewAssetLoader, private val onExternalLink: (Uri) -> Unit) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (request.url.host != READER_HOST) {
            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        }
        return loader.shouldInterceptRequest(request.url)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (request.url.host == READER_HOST) return false
        onExternalLink(request.url)
        return true
    }
}
