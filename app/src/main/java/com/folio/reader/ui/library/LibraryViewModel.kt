package com.folio.reader.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.reader.AppContainer
import com.folio.reader.container
import com.folio.reader.data.db.BookEntity
import com.folio.reader.data.db.CollectionBookRef
import com.folio.reader.data.db.CollectionEntity
import com.folio.reader.data.db.CollectionWithBooks
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val books: List<BookEntity> = emptyList(),
    val continueReading: BookEntity? = null,
    val collections: List<CollectionWithBooks> = emptyList(),
    val filter: Long? = null,
    val importing: Int = 0,
    val loaded: Boolean = false,
)

class LibraryViewModel(private val c: AppContainer) : ViewModel() {

    private val filter = MutableStateFlow<Long?>(null)
    private val importing = MutableStateFlow(0)
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    val state: StateFlow<LibraryUiState> = combine(
        c.books.observeBooks(),
        c.db.collections().observeAll(),
        filter,
        importing,
    ) { books, collections, f, imp ->
        val visible = if (f == null) books else collections.firstOrNull { it.collection.id == f }?.books
            ?.let { inColl -> val ids = inColl.map { it.id }.toSet(); books.filter { it.id in ids } }
            .orEmpty()
        LibraryUiState(
            books = visible,
            continueReading = books.firstOrNull { it.lastOpenedAt != null && it.progress < 0.995f },
            collections = collections,
            filter = f,
            importing = imp,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    val stats = c.stats.summary.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.folio.reader.data.StatsSummary())

    fun setFilter(id: Long?) = filter.update { id }

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            importing.update { it + uris.size }
            var ok = 0
            for (uri in uris) {
                runCatching { c.books.import(uri) }
                    .onSuccess { ok++ }
                    .onFailure { _messages.send("Couldn't import: ${it.message ?: "invalid file"}") }
                importing.update { it - 1 }
            }
            if (ok > 0) _messages.send(if (ok == 1) "Book added to your library" else "$ok books added")
        }
    }

    fun delete(book: BookEntity) = viewModelScope.launch {
        c.books.delete(book.id)
        _messages.send("\"${book.title}\" deleted")
    }

    suspend fun collectionIdsFor(bookId: String) = c.db.collections().collectionIdsFor(bookId).toSet()

    fun setMembership(bookId: String, collectionId: Long, member: Boolean) = viewModelScope.launch {
        if (member) c.db.collections().addBook(CollectionBookRef(collectionId, bookId))
        else c.db.collections().removeBook(collectionId, bookId)
    }

    fun createCollection(name: String, withBook: String? = null) = viewModelScope.launch {
        val id = c.db.collections().insert(CollectionEntity(name = name.trim(), createdAt = System.currentTimeMillis()))
        if (withBook != null) c.db.collections().addBook(CollectionBookRef(id, withBook))
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { LibraryViewModel(container) }
        }
    }
}
