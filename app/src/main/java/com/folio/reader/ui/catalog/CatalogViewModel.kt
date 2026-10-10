package com.folio.reader.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.reader.AppContainer
import com.folio.reader.container
import com.folio.reader.data.catalog.CatalogBook
import com.folio.reader.data.catalog.CatalogDetails
import com.folio.reader.data.catalog.CatalogHttp
import com.folio.reader.data.catalog.CatalogSource
import com.folio.reader.data.catalog.GutendexClient
import com.folio.reader.data.catalog.StandardEbooksClient
import com.folio.reader.data.db.BookEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class CatalogLanguage(val label: String, val codes: List<String>) {
    EN_ES("English & Spanish", listOf("en", "es")),
    EN("English", listOf("en")),
    ES("Spanish", listOf("es")),
    ANY("Any language", emptyList());

    /** Standard Ebooks only publishes English editions. */
    val includesEnglish get() = codes.isEmpty() || "en" in codes
}

data class SourceResults(
    val books: List<CatalogBook> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

sealed interface DownloadState {
    /** [progress] is null while the size is unknown. */
    data class Running(val progress: Float?) : DownloadState
    data class Done(val bookId: String) : DownloadState
    data class Failed(val message: String) : DownloadState
}

data class CatalogUiState(
    val query: String = "",
    /** The query the results are for (blank: browsing). */
    val searched: String = "",
    val source: CatalogSource? = null,
    val language: CatalogLanguage = CatalogLanguage.EN_ES,
    val results: Map<CatalogSource, SourceResults> = emptyMap(),
    val downloads: Map<String, DownloadState> = emptyMap(),
    /** Catalog key → id of the same book already in the library. */
    val owned: Map<String, String> = emptyMap(),
    val details: Map<String, CatalogDetails> = emptyMap(),
) {
    /** Sources shown for the current filters, Standard Ebooks first: its editions are the nicer ones to read. */
    val visibleSources: List<CatalogSource>
        get() = CatalogSource.entries.filter { s ->
            (source == null || source == s) && (s != CatalogSource.STANDARD_EBOOKS || language.includesEnglish)
        }

    fun libraryIdFor(book: CatalogBook): String? =
        (downloads[book.key] as? DownloadState.Done)?.bookId ?: owned[book.key]
}

class CatalogViewModel(private val c: AppContainer) : ViewModel() {

    private val gutenberg = GutendexClient()
    private val standard = StandardEbooksClient()
    private val _state = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val searches = mutableMapOf<CatalogSource, Job>()
    private var library: List<BookEntity> = emptyList()

    init {
        viewModelScope.launch {
            c.books.observeBooks().collect { books ->
                library = books
                refreshOwned()
            }
        }
        reload()
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun search() {
        _state.update { it.copy(searched = it.query.trim()) }
        reload()
    }

    fun clearSearch() {
        _state.update { it.copy(query = "", searched = "") }
        reload()
    }

    fun setSource(source: CatalogSource?) {
        _state.update { it.copy(source = source) }
        reload()
    }

    fun setLanguage(language: CatalogLanguage) {
        _state.update { it.copy(language = language) }
        reload()
    }

    fun loadMore(source: CatalogSource) {
        val current = _state.value.results[source] ?: return
        if (current.loading || !current.hasMore) return
        load(source, current.page + 1)
    }

    fun retry(source: CatalogSource) = load(source, (_state.value.results[source]?.page ?: 0) + 1)

    private fun reload() {
        searches.values.forEach { it.cancel() }
        searches.clear()
        _state.update { it.copy(results = emptyMap()) }
        _state.value.visibleSources.forEach { load(it, 1) }
    }

    private fun load(source: CatalogSource, page: Int) {
        val s = _state.value
        val query = s.searched
        val languages = s.language.codes
        setResults(source) { it.copy(loading = true, error = null) }
        searches[source]?.cancel()
        searches[source] = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    when (source) {
                        CatalogSource.GUTENBERG -> gutenberg.search(query, languages, page)
                        CatalogSource.STANDARD_EBOOKS -> standard.search(query, page)
                    }
                }
                setResults(source) { old ->
                    val books = if (page == 1) result.books else (old.books + result.books).distinctBy { it.key }
                    SourceResults(books, page, result.hasMore)
                }
                refreshOwned()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setResults(source) { it.copy(loading = false, error = friendly(e)) }
            }
        }
    }

    private fun setResults(source: CatalogSource, change: (SourceResults) -> SourceResults) =
        _state.update { it.copy(results = it.results + (source to change(it.results[source] ?: SourceResults()))) }

    /** The one-line summary and length live on the Standard Ebooks book page; fetched when its sheet opens. */
    fun loadDetails(book: CatalogBook) {
        if (book.source != CatalogSource.STANDARD_EBOOKS || book.key in _state.value.details) return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { standard.details(book) } }
                .onSuccess { d -> _state.update { it.copy(details = it.details + (book.key to d)) } }
        }
    }

    fun download(book: CatalogBook) {
        val current = _state.value.downloads[book.key]
        if (current is DownloadState.Running || current is DownloadState.Done) return
        setDownload(book, DownloadState.Running(null))
        viewModelScope.launch {
            val file = File(c.appContext.cacheDir, "catalog-${UUID.randomUUID()}.epub")
            try {
                val url = book.epubUrl ?: (_state.value.details[book.key]
                    ?: withContext(Dispatchers.IO) { standard.details(book) }).epubUrl
                var shown = -1
                withContext(Dispatchers.IO) {
                    CatalogHttp.downloadEpub(url, file) { p ->
                        val percent = p?.let { (it * 100).toInt() } ?: -1
                        if (percent != shown) {
                            shown = percent
                            setDownload(book, DownloadState.Running(p))
                        }
                    }
                }
                val id = c.books.importFile(file, "${book.title}.epub")
                setDownload(book, DownloadState.Done(id))
                _messages.send("“${book.title}” added to your library")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setDownload(book, DownloadState.Failed(friendly(e)))
                _messages.send("Couldn't download “${book.title}”: ${friendly(e)}")
            } finally {
                file.delete()
            }
        }
    }

    private fun setDownload(book: CatalogBook, d: DownloadState) =
        _state.update { it.copy(downloads = it.downloads + (book.key to d)) }

    /** Marks results the reader already has (same title, and an author whose surname starts the same). */
    private fun refreshOwned() {
        val byTitle = library.groupBy { normalizeTitle(it.title) }
        val owned = _state.value.results.values.flatMap { it.books }.mapNotNull { book ->
            val match = byTitle[normalizeTitle(book.title)]?.firstOrNull { sameAuthor(it.author, book.author) }
            match?.let { book.key to it.id }
        }.toMap()
        _state.update { it.copy(owned = owned) }
    }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection"
        is java.net.SocketTimeoutException -> "The site took too long to answer. Try again."
        else -> e.message ?: "Something went wrong"
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { CatalogViewModel(container) }
        }

        /** "Frankenstein; Or, The Modern Prometheus" and "Frankenstein" both become "frankenstein". */
        internal fun normalizeTitle(title: String): String =
            title.lowercase().split(';', ':', '\n').first().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
                .removePrefix("the ").removePrefix("a ")

        /** Transliterations differ between editions (Dostoevsky, Dostoyevsky): compare the start of the surnames. */
        internal fun sameAuthor(a: String, b: String): Boolean {
            fun surnames(s: String) = s.split('&', ',').mapNotNull { name ->
                name.trim().split(' ').lastOrNull()?.lowercase()?.filter { it.isLetter() }?.take(4)?.ifBlank { null }
            }.toSet()
            val sa = surnames(a)
            return sa.isEmpty() || surnames(b).any { it in sa }
        }
    }
}
