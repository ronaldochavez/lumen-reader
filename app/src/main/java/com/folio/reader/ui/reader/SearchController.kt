package com.folio.reader.ui.reader

import com.folio.reader.AppContainer
import com.folio.reader.ai.SpoilerFreeContextBuilder
import com.folio.reader.data.db.ChapterEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

data class SearchHit(
    val spineIndex: Int,
    val chapterTitle: String,
    /** Which match within the chapter (0-based), so the reader can jump to the same one. */
    val occurrence: Int,
    val before: String,
    val match: String,
    val after: String,
)

data class SearchState(
    val query: String = "",
    /** Spoiler-safe by default: later results would show text the reader hasn't reached. */
    val onlyUpToHere: Boolean = true,
    val results: List<SearchHit> = emptyList(),
    val searching: Boolean = false,
    val truncated: Boolean = false,
)

/** Full-text search over the extracted chapter (or PDF page) texts. Case- and accent-insensitive. */
class SearchController(
    private val scope: CoroutineScope,
    private val c: AppContainer,
    private val bookId: String,
    private val chapters: () -> List<ChapterEntity>,
    /** Current spine index and the fraction of it read. */
    private val position: () -> Pair<Int, Float>,
) {
    private val _state = MutableStateFlow(SearchState())
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        rerun(debounce = true)
    }

    fun setOnlyUpToHere(v: Boolean) {
        _state.update { it.copy(onlyUpToHere = v) }
        rerun(debounce = false)
    }

    private fun rerun(debounce: Boolean) {
        job?.cancel()
        val q = _state.value.query.trim()
        if (q.length < 2) {
            _state.update { it.copy(results = emptyList(), searching = false, truncated = false) }
            return
        }
        job = scope.launch {
            if (debounce) delay(300)
            _state.update { it.copy(searching = true) }
            val (spine, fraction) = position()
            val hits = withContext(Dispatchers.IO) { search(q, if (_state.value.onlyUpToHere) spine to fraction else null) }
            _state.update { it.copy(results = hits.take(MAX_RESULTS), truncated = hits.size > MAX_RESULTS, searching = false) }
        }
    }

    private suspend fun search(query: String, limit: Pair<Int, Float>?): List<SearchHit> {
        val regex = patternFor(query)
        val out = mutableListOf<SearchHit>()
        for (ch in chapters()) {
            if (limit != null && ch.spineIndex > limit.first) break
            val text = c.books.chapterText(bookId, ch.spineIndex)
            if (text.isEmpty()) continue
            val folded = fold(text)
            val endLimit = if (limit != null && ch.spineIndex == limit.first) (text.length * limit.second).toInt() else text.length
            var n = 0
            for (m in regex.findAll(folded)) {
                if (m.range.first >= endLimit) break
                val s = m.range.first
                val e = m.range.last + 1
                out += SearchHit(
                    spineIndex = ch.spineIndex,
                    chapterTitle = SpoilerFreeContextBuilder.label(ch),
                    occurrence = n++,
                    before = text.substring((s - 60).coerceAtLeast(0), s).replace('\n', ' ').trimStart(),
                    match = text.substring(s, e),
                    after = text.substring(e, (e + 80).coerceAtMost(if (limit != null && ch.spineIndex == limit.first) endLimit.coerceAtLeast(e) else text.length)).replace('\n', ' '),
                )
                if (out.size > MAX_RESULTS) return out
            }
        }
        return out
    }

    companion object {
        const val MAX_RESULTS = 300

        /**
         * Lowercases and strips accents one UTF-16 unit at a time, so offsets in the folded text match the original.
         * reader.js folds the same way, which keeps the n-th match identical on both sides.
         */
        fun fold(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s) {
                val base = if (ch.code < 128) ch else Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).firstOrNull() ?: ch
                sb.append(base.lowercaseChar())
            }
            return sb.toString()
        }

        /** Words of the folded query, any run of whitespace between them. */
        fun patternFor(query: String): Regex =
            Regex(fold(query).trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) })
    }
}
