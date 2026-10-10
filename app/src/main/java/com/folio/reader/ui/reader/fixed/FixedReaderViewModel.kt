package com.folio.reader.ui.reader.fixed

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.reader.AppContainer
import com.folio.reader.ai.BookContext
import com.folio.reader.ai.ReadingSnapshotCache
import com.folio.reader.ai.SpoilerFreeContextBuilder
import com.folio.reader.container
import com.folio.reader.data.db.BookFormat
import com.folio.reader.data.db.BookmarkEntity
import com.folio.reader.ui.reader.AskController
import com.folio.reader.ui.reader.ReaderSheet
import com.folio.reader.ui.reader.ReaderUiState
import com.folio.reader.ui.reader.ReturnPoint
import com.folio.reader.ui.reader.ReadingSessionTracker
import com.folio.reader.ui.reader.ReadAloudState
import com.folio.reader.ui.reader.TTS_RATES
import com.folio.reader.ui.reader.TtsPlayer
import com.folio.reader.ui.reader.splitSentences
import com.folio.reader.ui.reader.SearchController
import com.folio.reader.ui.reader.SearchHit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Reader state for page-based books (PDF, comics). Reuses [ReaderUiState] so the chrome and sheets are shared
 * with the reflowable reader; here a "chapter" is one page.
 */
class FixedReaderViewModel(savedState: SavedStateHandle, private val c: AppContainer) : ViewModel() {

    val bookId: String = checkNotNull(savedState["bookId"])

    private val _ui = MutableStateFlow(ReaderUiState())
    val ui = _ui.asStateFlow()

    private val _format = MutableStateFlow(BookFormat.PDF)
    val format = _format.asStateFlow()

    private val _pageRequests = Channel<Int>(Channel.CONFLATED)
    val pageRequests = _pageRequests.receiveAsFlow()

    val volumeKeys = c.settings.volumeKeysTurnPages

    private val askController = AskController(viewModelScope, c) { _, saver -> buildContext(saver) }
    val ask = askController.state

    init {
        viewModelScope.launch {
            val book = c.books.get(bookId) ?: return@launch
            val pages = c.books.chapters(bookId)
            _format.value = BookFormat.of(book.format)
            _ui.update {
                it.copy(
                    book = book,
                    chapters = pages,
                    toc = c.books.toc(bookId),
                    spineIndex = book.spineIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
                    progress = book.progress,
                    pageCount = pages.size,
                    chromeVisible = book.lastOpenedAt == null,
                )
            }
            c.books.markOpened(bookId)
        }
        viewModelScope.launch { c.settings.readerSettings.collect { s -> _ui.update { it.copy(settings = s) } } }
        viewModelScope.launch {
            c.db.annotations().observeBookmarks(bookId).collect { list -> _ui.update { it.copy(bookmarks = list) } }
        }
    }

    private val tracker = ReadingSessionTracker(viewModelScope, c.stats, bookId)
    private var lastPage = -1

    fun setVisible(visible: Boolean) {
        tracker.setVisible(visible)
        if (!visible && _readAloud.value.playing) pauseReadAloud()
    }

    // Each page is a "chapter" here, read in full once it's on screen.
    private val searchController = SearchController(viewModelScope, c, bookId, { _ui.value.chapters }, { _ui.value.spineIndex to 1f })
    val search = searchController.state
    fun setSearchQuery(q: String) = searchController.setQuery(q)
    fun setSearchOnlyUpToHere(v: Boolean) = searchController.setOnlyUpToHere(v)
    fun openSearchHit(hit: SearchHit) = jumpToPage(hit.spineIndex)

    fun onPageSettled(page: Int) {
        val pages = _ui.value.chapters
        val count = pages.size
        if (count == 0) return
        // Stats: moving to the next page means the previous one was read.
        val readForward = lastPage >= 0 && page == lastPage + 1
        if (readForward) tracker.pageTurned(pages[lastPage].charCount.toLong()) else tracker.touch()
        lastPage = page
        // After a jump the saved position stays where the reader was, until they read on here for a few pages.
        if (_ui.value.returnPoint != null && readForward && ++pagesSinceJump >= READ_ON_PAGES) {
            _ui.update { it.copy(returnPoint = null) }
        }
        val persist = _ui.value.returnPoint == null
        if (ttsResumeOnPage) {
            ttsResumeOnPage = false
            if (_readAloud.value.playing) speakPage(page, fromSentence = 0)
        }
        val progress = (page + 1f) / count
        _ui.update { it.copy(spineIndex = page, page = page, progress = progress) }
        viewModelScope.launch {
            if (persist) c.books.updateProgress(bookId, page, 0, 1f, progress)
            val book = _ui.value.book?.copy(progress = progress) ?: return@launch
            val left = c.stats.minutesLeft(book, count, page)
            _ui.update { it.copy(minutesLeft = left) }
        }
    }

    fun goToPage(page: Int) {
        closeSheet()
        _pageRequests.trySend(page.coerceIn(0, (_ui.value.chapters.size - 1).coerceAtLeast(0)))
    }

    fun seek(fraction: Float) = jumpToPage(((_ui.value.chapters.size - 1) * fraction).roundToInt())

    private var pagesSinceJump = 0

    /** A deliberate jump (search, index, bookmark, slider): remembers the current page so the reader can go back. */
    fun jumpToPage(page: Int) {
        val s = _ui.value
        if (s.returnPoint == null && s.book != null && page != s.spineIndex) {
            _ui.update { it.copy(returnPoint = ReturnPoint(s.spineIndex, 0, s.progress)) }
        }
        pagesSinceJump = 0
        goToPage(page)
    }

    fun returnToPoint() {
        val p = _ui.value.returnPoint ?: return
        _ui.update { it.copy(returnPoint = null) }
        goToPage(p.spineIndex)
    }

    /** "Stay here": drop the way back and save this page as the reading position. */
    fun dismissReturnPoint() {
        _ui.update { it.copy(returnPoint = null) }
        val s = _ui.value
        viewModelScope.launch { c.books.updateProgress(bookId, s.spineIndex, 0, 1f, s.progress) }
    }

    fun toggleChrome() = _ui.update { it.copy(chromeVisible = !it.chromeVisible) }

    fun openSheet(sheet: ReaderSheet) = _ui.update { it.copy(sheet = sheet, chromeVisible = false) }

    fun closeSheet() {
        if (_ui.value.sheet == ReaderSheet.ASK) askController.cancel()
        _ui.update { it.copy(sheet = ReaderSheet.NONE) }
    }

    fun toggleBookmark() = viewModelScope.launch {
        val s = _ui.value
        val existing = s.bookmarks.filter { it.spineIndex == s.spineIndex }
        if (existing.isNotEmpty()) {
            c.db.annotations().deleteBookmarks(existing.map { it.id })
        } else {
            c.db.annotations().insertBookmark(
                BookmarkEntity(
                    bookId = bookId,
                    spineIndex = s.spineIndex,
                    offset = 0,
                    progress = s.progress,
                    snippet = c.books.chapterText(bookId, s.spineIndex).take(140).replace('\n', ' ').ifBlank { s.chapterTitle },
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    fun deleteBookmark(b: BookmarkEntity) = viewModelScope.launch { c.db.annotations().deleteBookmarks(listOf(b.id)) }

    // ---------- Read aloud (PDF text layer) ----------

    private val _readAloud = MutableStateFlow(ReadAloudState())
    val readAloud = _readAloud.asStateFlow()
    private var tts: TtsPlayer? = null
    private var ttsCount = 0
    private var ttsCurrent = -1
    private var ttsSentences: List<String> = emptyList()
    private var ttsResumeOnPage = false

    private val ttsListener = object : TtsPlayer.Listener {
        override fun onTtsReady(ok: Boolean) {
            if (!ok) {
                _readAloud.update { it.copy(playing = false, error = "This device has no text-to-speech engine. Install \"Speech Services by Google\" from the Play Store.") }
                return
            }
            tts?.setLanguage(_ui.value.book?.language)
            tts?.setRate(_readAloud.value.rate)
            if (_readAloud.value.playing) speakPage(_ui.value.spineIndex, fromSentence = 0)
        }

        override fun onSentenceStart(index: Int) {
            ttsCurrent = index
            tracker.touch()
        }

        override fun onSentenceDone(index: Int) {
            if (index == ttsCount - 1 && _readAloud.value.playing) nextPageAloud()
        }

        override fun onTtsError() {
            _readAloud.update { it.copy(playing = false, error = "The voice stopped because of a text-to-speech engine error.") }
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
        if (player.isReady) speakPage(_ui.value.spineIndex, fromSentence = 0)
    }

    fun pauseReadAloud() {
        tts?.stop()
        _readAloud.update { it.copy(playing = false) }
    }

    fun resumeReadAloud() {
        _readAloud.update { it.copy(playing = true, error = null) }
        speakPage(_ui.value.spineIndex, fromSentence = ttsCurrent.coerceAtLeast(0))
    }

    fun stopReadAloud() {
        tts?.stop()
        ttsResumeOnPage = false
        ttsCurrent = -1
        _readAloud.update { ReadAloudState(rate = it.rate) }
    }

    fun cycleReadAloudRate() {
        val next = TTS_RATES[(TTS_RATES.indexOf(_readAloud.value.rate) + 1) % TTS_RATES.size]
        viewModelScope.launch { c.settings.setTtsRate(next) }
        _readAloud.update { it.copy(rate = next) }
        tts?.setRate(next)
        if (_readAloud.value.playing) speakPage(_ui.value.spineIndex, fromSentence = ttsCurrent.coerceAtLeast(0))
    }

    private fun speakPage(page: Int, fromSentence: Int) {
        viewModelScope.launch {
            val text = c.books.chapterText(bookId, page)
            val locale = _ui.value.book?.language?.let { java.util.Locale.forLanguageTag(it) } ?: java.util.Locale.getDefault()
            ttsSentences = splitSentences(text, locale)
            if (!_readAloud.value.playing) return@launch
            val rest = ttsSentences.drop(fromSentence)
            if (rest.isEmpty()) { nextPageAloud(); return@launch }
            ttsCount = ttsSentences.size
            ttsCurrent = fromSentence
            tts?.speak(rest, firstIndex = fromSentence, flush = true)
        }
    }

    private fun nextPageAloud() {
        val s = _ui.value
        if (s.spineIndex + 1 < s.chapters.size) {
            ttsResumeOnPage = true
            _pageRequests.trySend(s.spineIndex + 1)
        } else {
            stopReadAloud()
        }
    }

    override fun onCleared() {
        tts?.shutdown()
        super.onCleared()
    }

    // ---------- AI (PDFs with a text layer) ----------

    fun openAsk() {
        val s = _ui.value
        askController.open(null, SpoilerFreeContextBuilder.positionLabel(s.chapters.getOrNull(s.spineIndex), s.spineIndex, s.progress))
        _ui.update { it.copy(sheet = ReaderSheet.ASK, chromeVisible = false) }
    }

    /** "Where was I?": an empty chat about the book so far; the reader writes the first question. */
    fun openChat() = openAsk()

    fun askQuestion(q: String) = askController.ask(q)

    fun saveGeminiKey(key: String) = askController.saveGeminiKey(key)

    fun askWithWholeBook() = askController.readWholeBook()

    fun askAgain() = askController.askAgain()

    /** Pages before the current one, plus the whole current page (it's all on screen). */
    private suspend fun buildContext(saver: Boolean = false): BookContext {
        val s = _ui.value
        val book = s.book ?: error("Book not loaded")
        val current = c.books.chapterText(bookId, s.spineIndex)
        return c.contextBuilder.build(book, s.chapters, s.spineIndex, s.progress, current, c.ai.maxContextChars(), saver = saver)
    }

    suspend fun snapshotForGraph() {
        val s = _ui.value
        c.snapshots.put(bookId, ReadingSnapshotCache.Snapshot(s.spineIndex, c.books.chapterText(bookId, s.spineIndex), s.progress))
    }

    companion object {
        private const val READ_ON_PAGES = 3

        val Factory = viewModelFactory {
            initializer { FixedReaderViewModel(createSavedStateHandle(), container) }
        }
    }
}
