package com.folio.reader.ui.reader

import com.folio.reader.AppContainer
import com.folio.reader.ai.AiEvent
import com.folio.reader.ai.BookContext
import com.folio.reader.ai.ChatTurn
import com.folio.reader.ai.AiService
import com.folio.reader.ai.FreeQuotaExceededException
import com.folio.reader.ai.SpoilerFreeContextBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The "Ask Lumen" conversation, shared by the text reader and the page (PDF) reader.
 * [buildContext] returns the book text up to the reader's position; it's called once per conversation
 * (with the selection, if any) so follow-up questions reuse the same prompt-cached prefix.
 */
class AskController(
    private val scope: CoroutineScope,
    private val c: AppContainer,
    private val buildContext: suspend (selection: SelectionInfo?) -> BookContext,
) {
    private val _state = MutableStateFlow(AskState())
    val state = _state.asStateFlow()

    private var job: Job? = null
    private var context: BookContext? = null

    fun open(selection: SelectionInfo?, positionLabel: String) {
        cancel()
        _state.value = AskState(selection = selection, positionLabel = positionLabel)
        scope.launch { refreshProvider() }
    }

    private suspend fun refreshProvider() {
        // The one that will actually answer: the local AI is skipped when the computer can't be reached.
        val provider = runCatching { c.ai.currentProvider() }.getOrElse { c.ai.activeProvider.first() }
        _state.update { it.copy(provider = provider, needsKey = provider == null) }
    }

    /** Saves a free Gemini key entered right in the Ask sheet, then retries the question that needed it. */
    fun saveGeminiKey(key: String) {
        if (key.isBlank()) return
        scope.launch {
            c.settings.setGeminiKey(key)
            _state.update { it.copy(error = null) }
            refreshProvider()
            val pending = _state.value.messages.lastOrNull()?.takeIf { it.fromUser }
            if (pending != null) {
                _state.update { it.copy(messages = it.messages.dropLast(1)) }
                ask(pending.text)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        context = null
    }

    fun ask(question: String) {
        val start = _state.value
        if (start.streaming || question.isBlank()) return
        val history = start.messages.filter { it.text.isNotBlank() }
        _state.update {
            it.copy(
                messages = history + ChatTurn(true, question.trim()) + ChatTurn(false, ""),
                streaming = true,
                error = null,
                quotaExceeded = false,
            )
        }
        job = scope.launch {
            try {
                val ctx = context ?: buildContext(start.selection).also { context = it }
                _state.update { it.copy(positionLabel = ctx.positionLabel, note = ctx.truncationNote) }
                val readText = ctx.currentChapter.substringAfter('>').substringBefore(SpoilerFreeContextBuilder.READER_MARKER)
                if (ctx.priorChapters.isBlank() && readText.isBlank()) {
                    throw IllegalStateException("This book has no readable text up to here (a scanned PDF?), so the AI can't read it.")
                }
                c.ai.ask(ctx, start.selection?.text, history, question.trim()).collect { ev ->
                    when (ev) {
                        is AiEvent.Delta -> append(ev.text)
                        AiEvent.Refused -> append("\n\n(The AI couldn't answer this question.)")
                        AiEvent.Truncated -> append("…")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { st ->
                    st.copy(
                        error = AiService.friendlyError(e),
                        needsKey = AiService.needsSetup(e),
                        quotaExceeded = e is FreeQuotaExceededException,
                        messages = st.messages.dropLastWhile { !it.fromUser && it.text.isBlank() },
                    )
                }
            } finally {
                _state.update { it.copy(streaming = false) }
            }
        }
    }

    private fun append(delta: String) = _state.update { st ->
        val msgs = st.messages.toMutableList()
        val last = msgs.lastOrNull()
        if (last != null && !last.fromUser) msgs[msgs.lastIndex] = last.copy(text = last.text + delta)
        st.copy(messages = msgs)
    }
}
