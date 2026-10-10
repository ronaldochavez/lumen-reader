package com.folio.reader.ui.reader

import com.folio.reader.AppContainer
import com.folio.reader.ai.AiEvent
import com.folio.reader.ai.AnswerCache
import com.folio.reader.ai.BookContext
import com.folio.reader.ai.ChatTurn
import com.folio.reader.ai.AiService
import com.folio.reader.ai.FreeQuotaExceededException
import com.folio.reader.ai.Prompts
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
 * [buildContext] returns the book text up to the reader's position (with `saver`, only recent pages plus passages
 * about the selection); it's called once per conversation so follow-up questions reuse the same prompt-cached prefix.
 */
class AskController(
    private val scope: CoroutineScope,
    private val c: AppContainer,
    private val buildContext: suspend (selection: SelectionInfo?, saver: Boolean) -> BookContext,
) {
    private val _state = MutableStateFlow(AskState())
    val state = _state.asStateFlow()

    private var job: Job? = null
    private var context: BookContext? = null
    /** The reader asked to read the whole book in this conversation ("Read the whole book"). */
    private var wholeBook = false
    private val answers = AnswerCache(c.appContext.filesDir)

    fun open(selection: SelectionInfo?, positionLabel: String) {
        cancel()
        wholeBook = false
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

    /** Asks the last question again with everything up to the reader's position, for this and later questions. */
    fun readWholeBook() {
        if (_state.value.streaming) return
        wholeBook = true
        context = null
        askLastAgain()
    }

    /** Asks the last question again instead of showing the saved answer. */
    fun askAgain() {
        if (_state.value.streaming) return
        askLastAgain()
    }

    private fun askLastAgain() {
        val msgs = _state.value.messages
        val lastQuestion = msgs.indexOfLast { it.fromUser }.takeIf { it >= 0 } ?: return
        _state.update { it.copy(messages = msgs.take(lastQuestion)) }
        ask(msgs[lastQuestion].text, fresh = true)
    }

    /** @param fresh skip the saved answers (the reader asked for a new one). */
    fun ask(question: String, fresh: Boolean = false) {
        val start = _state.value
        if (start.streaming || question.isBlank()) return
        val history = start.messages.filter { it.text.isNotBlank() }
        _state.update {
            it.copy(
                messages = history + ChatTurn(true, question.trim()) + ChatTurn(false, ""),
                streaming = true,
                error = null,
                quotaExceeded = false,
                fromCache = false,
                tokens = null,
            )
        }
        job = scope.launch {
            try {
                val saver = !wholeBook && c.settings.aiSaver.first()
                val ctx = context ?: buildContext(start.selection, saver).also { context = it }
                _state.update {
                    it.copy(
                        positionLabel = ctx.positionLabel,
                        note = ctx.truncationNote,
                        saver = ctx.saver,
                        wholeBookTokens = SpoilerFreeContextBuilder.tokensFor(ctx.fullChars),
                    )
                }
                val readText = ctx.currentChapter.substringAfter('>').substringBefore(SpoilerFreeContextBuilder.READER_MARKER)
                if (ctx.priorChapters.isBlank() && readText.isBlank()) {
                    throw IllegalStateException("This book has no readable text up to here (a scanned PDF?), so the AI can't read it.")
                }
                // Only the opening question of a conversation can be reused: later ones depend on what came before.
                val cacheKey = AnswerCache.key(start.selection?.text, question, ctx.progress).takeIf { history.isEmpty() && ctx.bookId.isNotEmpty() }
                if (cacheKey != null && !fresh) {
                    val saved = answers.get(ctx.bookId, cacheKey)
                    if (saved != null) {
                        append(saved)
                        // Nothing was sent, so there's no trimmed text to describe or to widen.
                        _state.update { it.copy(fromCache = true, tokens = 0, note = null, saver = false) }
                        return@launch
                    }
                }
                var complete = true
                c.ai.ask(ctx, start.selection?.text, history, question.trim()).collect { ev ->
                    when (ev) {
                        is AiEvent.Delta -> append(ev.text)
                        AiEvent.Refused -> { complete = false; append("\n\n(The AI couldn't answer this question.)") }
                        AiEvent.Truncated -> { complete = false; append("…") }
                    }
                }
                val answer = _state.value.messages.lastOrNull()?.takeIf { !it.fromUser }?.text.orEmpty()
                _state.update { it.copy(tokens = estimateTokens(ctx, start.selection?.text, history, question, answer)) }
                if (cacheKey != null && complete && answer.isNotBlank()) answers.put(ctx.bookId, cacheKey, answer)
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

    companion object {
        /**
         * Tokens one question costs, roughly: the instructions, the book text, the conversation so far and the
         * answer. Shown so the reader can see what reading less of the book saves. (With Claude, the book part of
         * follow-up questions is read from the prompt cache, which is billed at a fraction of this.)
         */
        internal fun estimateTokens(ctx: BookContext, selection: String?, history: List<ChatTurn>, question: String, answer: String): Int {
            val chars = Prompts.ASK_SYSTEM.length + ctx.sentChars + Prompts.firstQuestion(selection, question).length +
                history.sumOf { it.text.length } + answer.length
            return SpoilerFreeContextBuilder.tokensFor(chars)
        }
    }
}
