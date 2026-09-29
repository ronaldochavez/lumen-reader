package com.folio.reader.ui.graph

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.reader.AppContainer
import com.folio.reader.ai.AiService
import com.folio.reader.ai.GraphData
import com.folio.reader.ai.SpoilerFreeContextBuilder
import com.folio.reader.container
import com.folio.reader.data.db.BookEntity
import com.folio.reader.data.db.CharacterGraphEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class GraphUiState(
    val book: BookEntity? = null,
    val graph: GraphData? = null,
    val generatedAt: String? = null,
    val generatedProgress: Float = 0f,
    val currentLabel: String = "",
    val currentProgress: Float = 0f,
    val loading: Boolean = false,
    val error: String? = null,
    val needsKey: Boolean = false,
) {
    /** The saved map is behind the reader's position by a noticeable amount. */
    val outdated: Boolean get() = graph != null && currentProgress - generatedProgress > 0.01f

    /** The saved map was built further into the book than the reader is now (they jumped back): may spoil. */
    val aheadOfReader: Boolean get() = graph != null && generatedProgress - currentProgress > 0.01f
}

class GraphViewModel(savedState: SavedStateHandle, private val c: AppContainer) : ViewModel() {
    private val bookId: String = checkNotNull(savedState["bookId"])
    private val _state = MutableStateFlow(GraphUiState())
    val state = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    init {
        viewModelScope.launch {
            val book = c.books.get(bookId) ?: return@launch
            val snapshot = c.snapshots.get(bookId)
            val chapters = c.books.chapters(bookId)
            val spine = snapshot?.spineIndex ?: book.spineIndex
            val progress = snapshot?.progress ?: book.progress
            _state.update {
                it.copy(
                    book = book,
                    currentProgress = progress,
                    currentLabel = SpoilerFreeContextBuilder.positionLabel(chapters.getOrNull(spine), spine, progress),
                )
            }
        }
        viewModelScope.launch {
            c.db.graphs().observe(bookId).collect { saved ->
                if (saved == null) return@collect
                val data = runCatching { json.decodeFromString(GraphData.serializer(), saved.json) }.getOrNull()
                _state.update { it.copy(graph = data, generatedAt = saved.positionLabel, generatedProgress = saved.progress) }
            }
        }
    }

    fun generate() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null, needsKey = false) }
        viewModelScope.launch {
            try {
                val book = c.books.get(bookId) ?: error("Libro no encontrado")
                val chapters = c.books.chapters(bookId)
                val snapshot = c.snapshots.get(bookId)
                val spine = snapshot?.spineIndex ?: book.spineIndex
                val progress = snapshot?.progress ?: book.progress
                val ctx = c.contextBuilder.build(book, chapters, spine, progress, snapshot?.textUpToPage, c.ai.maxContextChars())
                val graph = c.ai.extractCharacterGraph(ctx)
                c.db.graphs().upsert(
                    CharacterGraphEntity(
                        bookId = bookId,
                        json = json.encodeToString(GraphData.serializer(), graph),
                        spineIndex = spine,
                        progress = progress,
                        positionLabel = ctx.positionLabel,
                        createdAt = System.currentTimeMillis(),
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(error = AiService.friendlyError(e), needsKey = AiService.needsSetup(e))
                }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { GraphViewModel(createSavedStateHandle(), container) }
        }
    }
}
