package com.folio.reader.ui.reader

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.reader.AppContainer
import com.folio.reader.ai.AiEvent
import com.folio.reader.ai.AiProvider
import com.folio.reader.ai.AiService
import com.folio.reader.ai.Prompts
import com.folio.reader.ai.BookContext
import com.folio.reader.ai.ChatTurn
import com.folio.reader.ai.ClaudeService
import com.folio.reader.ai.ReadingSnapshotCache
import com.folio.reader.ai.SpoilerFreeContextBuilder
import com.folio.reader.container
import com.folio.reader.data.ReaderSettings
import com.folio.reader.data.db.BookEntity
import com.folio.reader.data.db.BookmarkEntity
import com.folio.reader.data.db.ChapterEntity
import com.folio.reader.data.db.HighlightEntity
import com.folio.reader.data.db.TocEntity
import com.folio.reader.data.epub.EpubParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.max

sealed interface NavTarget {
    data class Offset(val value: Int, val flashEnd: Int? = null) : NavTarget
    data object End : NavTarget
    data class Fraction(val value: Float) : NavTarget
    data class Fragment(val id: String) : NavTarget
    /** The n-th match of a search query within the chapter. */
    data class Search(val query: String, val occurrence: Int) : NavTarget
}

enum class ReaderSheet { NONE, TOC, ANNOTATIONS, SETTINGS, ASK, SEARCH }

data class ReturnPoint(val spineIndex: Int, val offset: Int, val progress: Float)

data class ReaderUiState(
    val book: BookEntity? = null,
    val chapters: List<ChapterEntity> = emptyList(),
    val toc: List<TocEntity> = emptyList(),
    val spineIndex: Int = 0,
    val chapterUrl: String? = null,
    val loadToken: Int = 0,
    val page: Int = 0,
    val pageCount: Int = 1,
    val pageStart: Int = 0,
    val pageEnd: Int = 0,
    val progress: Float = 0f,
    val chromeVisible: Boolean = false,
    val settings: ReaderSettings = ReaderSettings(),
    val highlights: List<HighlightEntity> = emptyList(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val highlightColor: String = "yellow",
    val sheet: ReaderSheet = ReaderSheet.NONE,
    val editingHighlight: HighlightEntity? = null,
    val editingNote: Boolean = false,
    /** Estimated minutes to finish the book at the reader's pace. */
    val minutesLeft: Long? = null,
    /** Where the reader was before a jump (search, TOC, note, slider, link), until they go back or read on. */
    val returnPoint: ReturnPoint? = null,
) {
    val chapterTitle: String
        get() = chapters.getOrNull(spineIndex)?.let(SpoilerFreeContextBuilder::label).orEmpty()

    val isBookmarked: Boolean
        get() = bookmarks.any { it.spineIndex == spineIndex && it.offset >= pageStart && it.offset < max(pageEnd, pageStart + 1) }
}

data class AskState(
    val selection: SelectionInfo? = null,
    val messages: List<ChatTurn> = emptyList(),
    val streaming: Boolean = false,
    val error: String? = null,
    val needsKey: Boolean = false,
    /** The free Gemini quota ran out: suggest adding a personal Anthropic key. */
    val quotaExceeded: Boolean = false,
    val provider: AiProvider? = null,
    val positionLabel: String = "",
    val note: String? = null,
)

data class DefineState(
    val selection: SelectionInfo,
    val text: String = "",
    val streaming: Boolean = false,
    val error: String? = null,
    val needsKey: Boolean = false,
)

sealed interface ReaderEvent {
    data class Message(val text: String) : ReaderEvent
    data class OpenExternal(val uri: Uri) : ReaderEvent
    data class Copy(val text: String) : ReaderEvent
}

class JsCall(val script: String, val result: CompletableDeferred<String>?)

class ReaderViewModel(savedState: SavedStateHandle, private val c: AppContainer) : ViewModel() {

    val bookId: String = checkNotNull(savedState["bookId"])

    private val _ui = MutableStateFlow(ReaderUiState())
    val ui = _ui.asStateFlow()

    val volumeKeys = c.settings.volumeKeysTurnPages

    private val askController = AskController(viewModelScope, c) { selectionEnd -> buildAskContext(selectionEnd) }
    val ask = askController.state

    private val _js = Channel<JsCall>(Channel.UNLIMITED)
    val js = _js.receiveAsFlow()

    private val _events = Channel<ReaderEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var chapterReady = false
    private var pendingTarget: NavTarget = NavTarget.Offset(0)
    private var prefixChars = LongArray(0)
    private var lastSnippet = ""

    private val tracker = ReadingSessionTracker(viewModelScope, c.stats, bookId)
    /** Absolute char position of the last page start, to measure how much was actually read. */
    private var lastAbsPos = -1L

    fun setVisible(visible: Boolean) {
        tracker.setVisible(visible)
        // No background audio service yet: read-aloud pauses when the app leaves the screen.
        if (!visible && _readAloud.value.playing) pauseReadAloud()
    }

    private val searchController = SearchController(
        viewModelScope, c, bookId,
        chapters = { _ui.value.chapters },
        // Current chapter and the fraction of it read up to the end of the visible page.
        position = { _ui.value.spineIndex to lastChapterFraction },
    )
    val search = searchController.state
    fun setSearchQuery(q: String) = searchController.setQuery(q)
    fun setSearchOnlyUpToHere(v: Boolean) = searchController.setOnlyUpToHere(v)

    fun openSearchHit(hit: SearchHit) {
        closeSheet()
        jumpTo(hit.spineIndex, NavTarget.Search(searchController.state.value.query, hit.occurrence))
    }

    private var lastChapterFraction = 0f

    init {
        viewModelScope.launch {
            val book = c.books.get(bookId) ?: return@launch
            val chapters = c.books.chapters(bookId)
            val toc = c.books.toc(bookId)
            prefixChars = LongArray(chapters.size + 1).also { arr ->
                chapters.forEachIndexed { i, ch -> arr[i + 1] = arr[i] + ch.charCount }
            }
            val spine = book.spineIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
            pendingTarget = NavTarget.Offset(book.chapterOffset)
            _ui.update {
                it.copy(
                    book = book,
                    chapters = chapters,
                    toc = toc,
                    spineIndex = spine,
                    progress = book.progress,
                    chapterUrl = chapters.getOrNull(spine)?.let { ch -> chapterUrl(bookId, ch.href) },
                    loadToken = it.loadToken + 1,
                    chromeVisible = book.lastOpenedAt == null,
                )
            }
            c.books.markOpened(bookId)
        }
        viewModelScope.launch {
            c.settings.readerSettings.collect { s ->
                _ui.update { it.copy(settings = s) }
                if (chapterReady) runJs("Folio.applySettings(${settingsJson(s)})")
            }
        }
        viewModelScope.launch {
            c.db.annotations().observeHighlights(bookId).collect { list ->
                _ui.update { it.copy(highlights = list) }
                if (chapterReady) runJs("Folio.setHighlights(${highlightsJson()})")
            }
        }
        viewModelScope.launch {
            c.db.annotations().observeBookmarks(bookId).collect { list -> _ui.update { it.copy(bookmarks = list) } }
        }
        viewModelScope.launch {
            c.settings.highlightColor.collect { color -> _ui.update { it.copy(highlightColor = color) } }
        }
    }

    // ---------- WebView plumbing ----------

    private fun runJs(script: String) {
        _js.trySend(JsCall(script, null))
    }

    private suspend fun evalJs(script: String): String {
        val result = CompletableDeferred<String>()
        _js.send(JsCall(script, result))
        return result.await()
    }

    fun onBridgeEvent(e: BridgeEvent) {
        when (e) {
            BridgeEvent.Ready -> {
                chapterReady = true
                runJs("Folio.init(${initJson()})")
            }
            is BridgeEvent.Page -> onPage(e)
            is BridgeEvent.Boundary -> if (e.forward) nextChapter() else previousChapter()
            BridgeEvent.ToggleChrome -> _ui.update { it.copy(chromeVisible = !it.chromeVisible) }
            is BridgeEvent.HighlightTap -> _ui.value.highlights.firstOrNull { it.id == e.id }?.let { h ->
                _ui.update { it.copy(editingHighlight = h, editingNote = false) }
            }
            is BridgeEvent.Link -> onLink(e.url)
        }
    }

    fun onChapterLoadStarted() {
        chapterReady = false
    }

    private fun onPage(e: BridgeEvent.Page) {
        if (!chapterReady) return
        // If the WebView is recreated (e.g. rotation), reopen at this spot.
        pendingTarget = NavTarget.Offset(e.anchor)
        lastAnchor = e.anchor
        val spine = _ui.value.spineIndex
        val fraction = if (e.total > 0) e.end.toFloat() / e.total else 1f
        lastChapterFraction = fraction
        val progress = overallProgress(spine, fraction)
        lastSnippet = e.snippet
        _ui.update {
            it.copy(page = e.page, pageCount = e.pageCount, pageStart = e.start, pageEnd = e.end, progress = progress)
        }

        // Stats: a forward move of up to a few pages counts as reading; jumps (TOC, slider) don't.
        val absPos = prefixChars.getOrElse(spine) { 0L } + e.start
        val delta = absPos - lastAbsPos
        val readForward = lastAbsPos >= 0 && delta in 1..MAX_READ_JUMP
        if (readForward) tracker.pageTurned(delta) else tracker.touch()
        lastAbsPos = absPos

        // After a jump the saved position stays where the reader was, until they read on here for a few pages.
        if (_ui.value.returnPoint != null && readForward && ++pagesSinceJump >= READ_ON_PAGES) {
            _ui.update { it.copy(returnPoint = null) }
        }
        val persist = _ui.value.returnPoint == null

        if (ttsResumeOnPage) {
            ttsResumeOnPage = false
            if (_readAloud.value.playing) speakFrom(e.start)
        }

        viewModelScope.launch {
            if (persist) c.books.updateProgress(bookId, spine, e.anchor, fraction, progress)
            val book = _ui.value.book?.copy(progress = progress) ?: return@launch
            val left = c.stats.minutesLeft(book, _ui.value.chapters.size, spine)
            _ui.update { it.copy(minutesLeft = left) }
        }
    }

    private fun overallProgress(spine: Int, fraction: Float): Float {
        val total = prefixChars.lastOrNull()?.takeIf { it > 0 } ?: return 0f
        val before = prefixChars.getOrElse(spine) { 0L }
        val current = prefixChars.getOrElse(spine + 1) { before } - before
        return ((before + fraction * current) / total).coerceIn(0f, 1f)
    }

    private fun onLink(url: String) {
        val prefix = BOOK_BASE_URL + bookId + "/"
        if (url.startsWith(prefix)) {
            val (path, fragment) = EpubParser.splitHref(url.removePrefix(prefix))
            val decoded = EpubParser.resolve("", path)
            val index = _ui.value.chapters.indexOfFirst { it.href == decoded }
            if (index >= 0) jumpTo(index, fragment?.let { NavTarget.Fragment(Uri.decode(it)) } ?: NavTarget.Offset(0))
        } else if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")) {
            _events.trySend(ReaderEvent.OpenExternal(Uri.parse(url)))
        }
    }

    // ---------- Navigation ----------

    fun goToChapter(index: Int, target: NavTarget) {
        val state = _ui.value
        if (index !in state.chapters.indices) {
            if (index >= state.chapters.size) _events.trySend(ReaderEvent.Message("Fin del libro 🎉"))
            return
        }
        if (index == state.spineIndex && chapterReady) {
            runJs(navigateScript(target))
            return
        }
        pendingTarget = target
        chapterReady = false
        _ui.update {
            it.copy(spineIndex = index, chapterUrl = chapterUrl(bookId, state.chapters[index].href), loadToken = it.loadToken + 1)
        }
    }

    fun nextChapter() = goToChapter(_ui.value.spineIndex + 1, NavTarget.Offset(0))
    fun previousChapter() {
        if (_ui.value.spineIndex > 0) goToChapter(_ui.value.spineIndex - 1, NavTarget.End)
    }

    /** A deliberate jump away from the reading position: remembers where the reader was so they can go back. */
    fun jumpTo(index: Int, target: NavTarget) {
        val s = _ui.value
        if (s.returnPoint == null && chapterReady && s.book != null) {
            _ui.update { it.copy(returnPoint = ReturnPoint(s.spineIndex, lastAnchor, s.progress)) }
        }
        pagesSinceJump = 0
        goToChapter(index, target)
    }

    /** Chapter skip buttons in the controls (unlike turning past the last page, these are jumps). */
    fun skipChapter(forward: Boolean) {
        val s = _ui.value
        if (forward) jumpTo(s.spineIndex + 1, NavTarget.Offset(0))
        else if (s.spineIndex > 0) jumpTo(s.spineIndex - 1, NavTarget.Offset(0))
    }

    fun returnToPoint() {
        val p = _ui.value.returnPoint ?: return
        _ui.update { it.copy(returnPoint = null) }
        goToChapter(p.spineIndex, NavTarget.Offset(p.offset))
    }

    /** "Stay here": drop the way back and save this page as the reading position. */
    fun dismissReturnPoint() {
        _ui.update { it.copy(returnPoint = null) }
        val s = _ui.value
        viewModelScope.launch { c.books.updateProgress(bookId, s.spineIndex, lastAnchor, lastChapterFraction, s.progress) }
    }

    private var pagesSinceJump = 0
    /** Reading position inside the current page, as reported by the page engine. */
    private var lastAnchor = 0

    fun nextPage() = runJs("Folio.next()")
    fun previousPage() = runJs("Folio.prev()")

    /** Jumps to an overall book fraction (from the progress slider). */
    fun seek(fraction: Float) {
        val total = prefixChars.lastOrNull() ?: return
        val target = (fraction.coerceIn(0f, 1f) * total).toLong()
        var index = prefixChars.indexOfLast { it <= target }.coerceIn(0, (_ui.value.chapters.size - 1).coerceAtLeast(0))
        if (index >= _ui.value.chapters.size) index = _ui.value.chapters.size - 1
        val start = prefixChars[index]
        val len = (prefixChars.getOrElse(index + 1) { start } - start).coerceAtLeast(1)
        jumpTo(index, NavTarget.Fraction(((target - start).toFloat() / len).coerceIn(0f, 1f)))
    }

    fun goToAnnotation(spineIndex: Int, offset: Int, flashEnd: Int? = null) {
        closeSheet()
        jumpTo(spineIndex, NavTarget.Offset(offset, flashEnd))
    }

    fun toggleChrome(visible: Boolean? = null) = _ui.update { it.copy(chromeVisible = visible ?: !it.chromeVisible) }

    fun openSheet(sheet: ReaderSheet) = _ui.update { it.copy(sheet = sheet, chromeVisible = false) }

    fun closeSheet() {
        if (_ui.value.sheet == ReaderSheet.ASK) askController.cancel()
        _ui.update { it.copy(sheet = ReaderSheet.NONE) }
    }

    // ---------- Settings ----------

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) = viewModelScope.launch {
        c.settings.updateReader(transform)
    }

    // ---------- Selection, highlights, bookmarks ----------

    fun onSelection(action: SelectionAction, info: SelectionInfo) {
        if (info.text.isBlank()) return
        when (action) {
            SelectionAction.ASK -> openAsk(info)
            SelectionAction.DEFINE -> define(info)
            SelectionAction.HIGHLIGHT -> viewModelScope.launch { addHighlight(info) }
            SelectionAction.NOTE -> viewModelScope.launch {
                val h = addHighlight(info)
                _ui.update { it.copy(editingHighlight = h, editingNote = true) }
            }
            SelectionAction.COPY -> _events.trySend(ReaderEvent.Copy(info.text))
        }
    }

    private suspend fun addHighlight(info: SelectionInfo): HighlightEntity {
        val h = HighlightEntity(
            bookId = bookId,
            spineIndex = _ui.value.spineIndex,
            startOffset = info.start,
            endOffset = info.end,
            text = info.text.trim(),
            color = _ui.value.highlightColor,
            createdAt = System.currentTimeMillis(),
        )
        val id = c.db.annotations().insertHighlight(h)
        return h.copy(id = id)
    }

    fun updateHighlight(h: HighlightEntity) = viewModelScope.launch {
        c.db.annotations().updateHighlight(h)
        c.settings.setHighlightColor(h.color)
        _ui.update { it.copy(editingHighlight = null, editingNote = false) }
    }

    fun deleteHighlight(h: HighlightEntity) = viewModelScope.launch {
        c.db.annotations().deleteHighlight(h.id)
        _ui.update { it.copy(editingHighlight = null, editingNote = false) }
    }

    fun dismissHighlightEditor() = _ui.update { it.copy(editingHighlight = null, editingNote = false) }

    fun askAboutHighlight(h: HighlightEntity) {
        dismissHighlightEditor()
        if (h.spineIndex != _ui.value.spineIndex) return
        openAsk(SelectionInfo(h.text, h.startOffset, h.endOffset))
    }

    fun toggleBookmark() = viewModelScope.launch {
        val s = _ui.value
        val existing = s.bookmarks.filter { it.spineIndex == s.spineIndex && it.offset >= s.pageStart && it.offset < max(s.pageEnd, s.pageStart + 1) }
        if (existing.isNotEmpty()) {
            c.db.annotations().deleteBookmarks(existing.map { it.id })
        } else {
            c.db.annotations().insertBookmark(
                BookmarkEntity(
                    bookId = bookId,
                    spineIndex = s.spineIndex,
                    offset = s.pageStart,
                    progress = s.progress,
                    snippet = lastSnippet.ifBlank { s.chapterTitle },
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    fun deleteBookmark(b: BookmarkEntity) = viewModelScope.launch { c.db.annotations().deleteBookmarks(listOf(b.id)) }

    // ---------- Read aloud ----------

    @kotlinx.serialization.Serializable
    private data class TtsSentence(val s: Int, val e: Int, val t: String)

    private val _readAloud = MutableStateFlow(ReadAloudState())
    val readAloud = _readAloud.asStateFlow()
    private var tts: TtsPlayer? = null
    private var ttsQueue: List<TtsSentence> = emptyList()
    private var ttsCurrent = -1
    private var ttsFetching = false
    private var ttsChapterEnd = false
    /** Start reading as soon as the next chapter reports its first page. */
    private var ttsResumeOnPage = false

    private val ttsListener = object : TtsPlayer.Listener {
        override fun onTtsReady(ok: Boolean) {
            if (!ok) {
                _readAloud.update { it.copy(playing = false, error = "Este dispositivo no tiene un motor de voz. Instala «Speech Services by Google» desde Play Store.") }
                return
            }
            tts?.setLanguage(_ui.value.book?.language)
            tts?.setRate(_readAloud.value.rate)
            if (_readAloud.value.playing) speakFrom(_ui.value.pageStart)
        }

        override fun onSentenceStart(index: Int) {
            ttsCurrent = index
            ttsQueue.getOrNull(index)?.let { runJs("Folio.ttsMark(${it.s}, ${it.e})") }
            tracker.touch()
            // Prefetch the next stretch while the last queued sentence plays, so there's no gap.
            if (index == ttsQueue.lastIndex && !ttsFetching && !ttsChapterEnd) fetchMore(ttsQueue[index].e)
        }

        override fun onSentenceDone(index: Int) {
            if (index == ttsQueue.lastIndex && ttsChapterEnd && _readAloud.value.playing) continueNextChapter()
        }

        override fun onTtsError() {
            _readAloud.update { it.copy(playing = false, error = "La voz se detuvo por un error del motor de texto a voz.") }
        }
    }

    init {
        viewModelScope.launch { c.settings.ttsRate.collect { r -> _readAloud.update { it.copy(rate = r) } } }
    }

    fun startReadAloud() {
        closeSheet()
        _readAloud.update { it.copy(active = true, playing = true, error = null) }
        _ui.update { it.copy(chromeVisible = false) }
        val player = tts ?: TtsPlayer(c.appContext, ttsListener).also { tts = it }
        if (player.isReady) speakFrom(_ui.value.pageStart)
    }

    fun pauseReadAloud() {
        tts?.stop()
        _readAloud.update { it.copy(playing = false) }
    }

    fun resumeReadAloud() {
        _readAloud.update { it.copy(playing = true, error = null) }
        speakFrom(ttsQueue.getOrNull(ttsCurrent)?.s ?: _ui.value.pageStart)
    }

    fun stopReadAloud() {
        tts?.stop()
        runJs("Folio.ttsClear()")
        ttsQueue = emptyList()
        ttsCurrent = -1
        ttsResumeOnPage = false
        _readAloud.update { ReadAloudState(rate = it.rate) }
    }

    fun cycleReadAloudRate() {
        val next = TTS_RATES[(TTS_RATES.indexOf(_readAloud.value.rate) + 1) % TTS_RATES.size]
        viewModelScope.launch { c.settings.setTtsRate(next) }
        _readAloud.update { it.copy(rate = next) }
        tts?.setRate(next)
        // Re-queue from the current sentence so the new speed applies right away.
        if (_readAloud.value.playing) speakFrom(ttsQueue.getOrNull(ttsCurrent)?.s ?: _ui.value.pageStart)
    }

    private suspend fun sentencesFrom(offset: Int): List<TtsSentence> {
        val lang = JsonPrimitive(_ui.value.book?.language.orEmpty())
        val raw = evalJs("Folio.sentencesFrom($offset, 3000, $lang)")
        return runCatching { Json.decodeFromString<List<TtsSentence>>(raw) }.getOrNull().orEmpty()
    }

    private fun speakFrom(offset: Int) {
        viewModelScope.launch {
            ttsChapterEnd = false
            ttsFetching = false
            val list = sentencesFrom(offset)
            if (!_readAloud.value.playing) return@launch
            if (list.isEmpty()) { continueNextChapter(); return@launch }
            ttsQueue = list
            ttsCurrent = -1
            tts?.speak(list.map { it.t }, firstIndex = 0, flush = true)
        }
    }

    private fun fetchMore(from: Int) {
        ttsFetching = true
        viewModelScope.launch {
            val more = sentencesFrom(from).filter { it.s >= from }
            ttsFetching = false
            if (!_readAloud.value.playing) return@launch
            if (more.isEmpty()) { ttsChapterEnd = true; return@launch }
            val first = ttsQueue.size
            ttsQueue = ttsQueue + more
            tts?.speak(more.map { it.t }, firstIndex = first, flush = false)
        }
    }

    private fun continueNextChapter() {
        val s = _ui.value
        if (s.spineIndex + 1 < s.chapters.size) {
            ttsResumeOnPage = true
            goToChapter(s.spineIndex + 1, NavTarget.Offset(0))
        } else {
            stopReadAloud()
            _events.trySend(ReaderEvent.Message("Fin del libro 🎉"))
        }
    }

    override fun onCleared() {
        tts?.shutdown()
        super.onCleared()
    }

    // ---------- Define / translate ----------

    private val _define = MutableStateFlow<DefineState?>(null)
    val define = _define.asStateFlow()
    private var defineJob: Job? = null

    /** Meaning of the selected word in its passage (and a translation if the book isn't in Spanish). */
    fun define(info: SelectionInfo) {
        defineJob?.cancel()
        _define.value = DefineState(selection = info, streaming = true)
        defineJob = viewModelScope.launch {
            try {
                val passage = runCatching {
                    Json.decodeFromString<String>(evalJs("Folio.contextAround(${info.start}, ${info.end})"))
                }.getOrNull().orEmpty().ifBlank { info.text }
                val book = _ui.value.book
                val request = Prompts.defineRequest(book?.title.orEmpty(), book?.language, passage, info.text.take(200))
                c.ai.quick(Prompts.DEFINE_SYSTEM, request, maxTokens = 600).collect { ev ->
                    when (ev) {
                        is AiEvent.Delta -> _define.update { it?.copy(text = it.text + ev.text) }
                        AiEvent.Refused -> _define.update { it?.copy(error = "La IA no pudo definir esto.") }
                        AiEvent.Truncated -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _define.update { it?.copy(error = AiService.friendlyError(e), needsKey = AiService.needsSetup(e)) }
            } finally {
                _define.update { it?.copy(streaming = false) }
            }
        }
    }

    fun closeDefine() {
        defineJob?.cancel()
        _define.value = null
    }

    fun saveGeminiKeyAndRetryDefine(key: String) = viewModelScope.launch {
        c.settings.setGeminiKey(key)
        _define.value?.selection?.let(::define)
    }

    /** From the definition card: keep talking about this passage in the full, spoiler-free chat. */
    fun askAboutDefined() {
        val sel = _define.value?.selection ?: return
        closeDefine()
        openAsk(sel)
    }

    // ---------- AI ----------

    fun openAsk(selection: SelectionInfo?) {
        val s = _ui.value
        askController.open(selection, SpoilerFreeContextBuilder.positionLabel(s.chapters.getOrNull(s.spineIndex), s.spineIndex, s.progress))
        _ui.update { it.copy(sheet = ReaderSheet.ASK, chromeVisible = false) }
    }

    /** "¿Dónde me quedé?": an empty chat about the book so far; the reader writes the first question. */
    fun openChat() = openAsk(null)

    fun askQuestion(question: String) = askController.ask(question)

    fun saveGeminiKey(key: String) = askController.saveGeminiKey(key)

    /** Text from the start of the book to the end of the visible page (or the selection, if it runs past it). */
    private suspend fun buildAskContext(selectionEnd: Int?): BookContext {
        val s = _ui.value
        val book = s.book ?: error("Libro no cargado")
        val cutoff = max(s.pageEnd, selectionEnd ?: 0)
        val partial = runCatching { Json.decodeFromString<String>(evalJs("Folio.textUpTo($cutoff)")) }.getOrNull()
        return c.contextBuilder.build(book, s.chapters, s.spineIndex, s.progress, partial, c.ai.maxContextChars())
    }

    /** Captures the exact reading position for the character-graph screen. */
    suspend fun snapshotForGraph() {
        val s = _ui.value
        val partial = runCatching { Json.decodeFromString<String>(evalJs("Folio.textUpTo(${s.pageEnd})")) }.getOrNull()
            ?: return
        c.snapshots.put(bookId, ReadingSnapshotCache.Snapshot(s.spineIndex, partial, s.progress))
    }

    // ---------- JSON for the JS side ----------

    private fun initJson(): String = buildJsonObject {
        put("settings", Json.parseToJsonElement(settingsJson(_ui.value.settings)))
        put("highlights", Json.parseToJsonElement(highlightsJson()))
        put("target", targetJson(pendingTarget))
    }.toString()

    private fun highlightsJson(): String = buildJsonArray {
        _ui.value.highlights.filter { it.spineIndex == _ui.value.spineIndex }.forEach { h ->
            add(buildJsonObject {
                put("id", h.id)
                put("start", h.startOffset)
                put("end", h.endOffset)
                put("color", h.color)
            })
        }
    }.toString()

    private fun targetJson(t: NavTarget) = buildJsonObject {
        when (t) {
            is NavTarget.Offset -> { put("type", "offset"); put("value", t.value); t.flashEnd?.let { put("flashEnd", it) } }
            NavTarget.End -> put("type", "end")
            is NavTarget.Fraction -> { put("type", "fraction"); put("value", t.value) }
            is NavTarget.Fragment -> { put("type", "fragment"); put("value", t.id) }
            is NavTarget.Search -> { put("type", "search"); put("value", t.query); put("n", t.occurrence) }
        }
    }

    private fun navigateScript(t: NavTarget): String = when (t) {
        is NavTarget.Offset -> "Folio.goToOffset(${t.value}, ${t.flashEnd ?: "null"})"
        NavTarget.End -> "Folio.goToFraction(1)"
        is NavTarget.Fraction -> "Folio.goToFraction(${t.value})"
        is NavTarget.Fragment -> "Folio.goToFragment(${JsonPrimitive(t.id)})"
        is NavTarget.Search -> "Folio.findOccurrence(${JsonPrimitive(t.query)}, ${t.occurrence})"
    }

    companion object {
        /** ~4 pages of text: anything bigger is a jump, not reading. */
        private const val MAX_READ_JUMP = 8_000L
        /** Page turns after a jump that mean "I'm reading here now" (drops the way back and saves the new spot). */
        private const val READ_ON_PAGES = 3

        fun cssColor(argb: Long) = String.format("#%06X", argb and 0xFFFFFF)

        fun settingsJson(s: ReaderSettings): String = buildJsonObject {
            put("bg", cssColor(s.theme.background))
            put("fg", cssColor(s.theme.foreground))
            put("link", cssColor(s.theme.link))
            put("serif", s.serif)
            put("fontSize", s.fontSize)
            put("lineHeight", s.lineHeight)
            put("margin", s.margin)
            put("theme", s.theme.name)
        }.toString()

        val Factory = viewModelFactory {
            initializer { ReaderViewModel(createSavedStateHandle(), container) }
        }
    }
}
