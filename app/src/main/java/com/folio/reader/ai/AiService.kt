package com.folio.reader.ai

import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.folio.reader.data.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class AiProvider(val label: String) {
    LOCAL("Local AI (your computer)"),
    CLAUDE("Claude (your key)"),
    GEMINI("Gemini (free)"),
}

/**
 * Picks the provider for each request, in this order: the local model on the reader's computer (free and private)
 * when it's reachable, then the personal Anthropic key, then the free Gemini tier. When the computer is off or on
 * another network, requests quietly go to the next configured provider. When the free Gemini quota runs out,
 * callers get [FreeQuotaExceededException] and can suggest a personal key.
 */
class AiService(
    private val settings: SettingsRepository,
    private val claude: ClaudeService,
    private val gemini: GeminiService,
    val local: OllamaService,
) : AiBackend {

    /** Configured providers, in priority order (reachability of the local one isn't checked here). */
    private val configured: Flow<List<AiProvider>> =
        combine(settings.localAiUrl, settings.apiKey, settings.geminiKey) { localUrl, anthropic, google ->
            listOfNotNull(
                AiProvider.LOCAL.takeIf { !localUrl.isNullOrBlank() },
                AiProvider.CLAUDE.takeIf { !anthropic.isNullOrBlank() },
                AiProvider.GEMINI.takeIf { !google.isNullOrBlank() },
            )
        }

    /** The first configured provider, or null if none is. */
    val activeProvider: Flow<AiProvider?> = configured.map { it.firstOrNull() }

    @Volatile private var reachability: Pair<Long, Boolean>? = null

    /** Is the local server up? Cached briefly so building a context and sending it agree on the provider. */
    private suspend fun localReachable(): Boolean {
        reachability?.let { (at, ok) -> if (System.currentTimeMillis() - at < REACH_CACHE_MS) return ok }
        return local.isReachable().also { reachability = System.currentTimeMillis() to it }
    }

    /** The provider that will answer this request: skips the local one when the computer can't be reached. */
    suspend fun currentProvider(): AiProvider? {
        val list = configured.first()
        if (list.isEmpty()) return null
        if (list.first() == AiProvider.LOCAL && !localReachable()) {
            return list.getOrNull(1) ?: throw LocalAiUnavailableException(settings.localAiUrl.first().orEmpty())
        }
        return list.first()
    }

    private suspend fun backend(): AiBackend = when (currentProvider()) {
        AiProvider.LOCAL -> local
        AiProvider.CLAUDE -> claude
        AiProvider.GEMINI -> gemini
        null -> throw MissingApiKeyException()
    }

    /** How much book text (in chars) the provider that will answer can take in one request. */
    suspend fun maxContextChars(): Int = when (currentProvider()) {
        AiProvider.LOCAL -> local.maxContextChars()
        AiProvider.GEMINI -> GeminiService.MAX_CONTEXT_CHARS
        else -> settings.model.first().maxContextChars
    }

    /** Forget the cached reachability (e.g. after the reader changes the address). */
    fun resetLocalReachability() { reachability = null }

    override fun ask(context: BookContext, selection: String?, history: List<ChatTurn>, question: String): Flow<AiEvent> = flow {
        emitAll(backend().ask(context, selection, history, question))
    }

    override suspend fun extractStructured(context: BookContext, task: StructuredTask): String =
        backend().extractStructured(context, task)

    suspend fun extractCharacterGraph(context: BookContext): GraphData =
        ClaudeService.graphJson.decodeFromString(GraphData.serializer(), extractStructured(context, StructuredTask.GRAPH))

    suspend fun extractEncyclopedia(context: BookContext): Encyclopedia =
        ClaudeService.graphJson.decodeFromString(Encyclopedia.serializer(), extractStructured(context, StructuredTask.ENCYCLOPEDIA))

    override fun quick(system: String, user: String, maxTokens: Int): Flow<AiEvent> = flow {
        emitAll(backend().quick(system, user, maxTokens))
    }

    override suspend fun testConnection(): String = backend().testConnection()

    companion object {
        private const val REACH_CACHE_MS = 20_000L

        fun friendlyError(e: Throwable): String = when (e) {
            is MissingApiKeyException, is FreeQuotaExceededException, is LocalAiUnavailableException -> e.message!!
            is GeminiApiException -> when {
                e.reason == "API_KEY_INVALID" || e.message?.contains("API key", ignoreCase = true) == true ->
                    "Your Gemini key isn't valid. Check it in Settings."
                e.status == "FAILED_PRECONDITION" && e.message?.contains("location", ignoreCase = true) == true ->
                    "Gemini's free plan isn't available in your country. Use an Anthropic key in Settings."
                e.httpCode == 403 -> "Your Gemini key doesn't have access to this model."
                e.httpCode >= 500 -> "Gemini is overloaded right now. Try again in a moment."
                else -> "Gemini returned an error: ${e.message}"
            }
            is UnauthorizedException, is PermissionDeniedException -> "Your Anthropic API key isn't valid. Check it in Settings."
            is RateLimitException -> "Too many requests in a row. Wait a moment and try again."
            is AnthropicServiceException -> "The AI isn't available right now (${e.statusCode()}). Try again."
            is IOException -> e.message ?: "No connection. Check your internet."
            else -> e.message ?: "Something went wrong."
        }

        /** Errors where the right next step is adding or fixing a key in Settings. */
        fun needsSetup(e: Throwable) = e is MissingApiKeyException || e is FreeQuotaExceededException ||
            (e is GeminiApiException && (e.reason == "API_KEY_INVALID" || e.httpCode == 403)) ||
            e is UnauthorizedException || e is PermissionDeniedException
    }
}
