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
import java.io.IOException

enum class AiProvider(val label: String) {
    CLAUDE("Claude (tu key)"),
    GEMINI("Gemini (gratis)"),
}

/**
 * Picks the provider for each request: the user's personal Anthropic key if they added one, otherwise the free
 * Gemini tier. When the free quota runs out, callers get [FreeQuotaExceededException] and can suggest a personal key.
 */
class AiService(
    private val settings: SettingsRepository,
    private val claude: ClaudeService,
    private val gemini: GeminiService,
) : AiBackend {

    /** The provider that will answer, or null if none is configured. */
    val activeProvider: Flow<AiProvider?> = combine(settings.apiKey, settings.geminiKey) { anthropic, google ->
        when {
            !anthropic.isNullOrBlank() -> AiProvider.CLAUDE
            !google.isNullOrBlank() -> AiProvider.GEMINI
            else -> null
        }
    }

    private suspend fun backend(): AiBackend = when (activeProvider.first()) {
        AiProvider.CLAUDE -> claude
        AiProvider.GEMINI -> gemini
        null -> throw MissingApiKeyException()
    }

    /** How much book text (in chars) the active provider can take in one request. */
    suspend fun maxContextChars(): Int = when (activeProvider.first()) {
        AiProvider.GEMINI -> GeminiService.MAX_CONTEXT_CHARS
        else -> settings.model.first().maxContextChars
    }

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
        fun friendlyError(e: Throwable): String = when (e) {
            is MissingApiKeyException, is FreeQuotaExceededException -> e.message!!
            is GeminiApiException -> when {
                e.reason == "API_KEY_INVALID" || e.message?.contains("API key", ignoreCase = true) == true ->
                    "Tu key de Gemini no es válida. Revísala en Ajustes."
                e.status == "FAILED_PRECONDITION" && e.message?.contains("location", ignoreCase = true) == true ->
                    "El plan gratis de Gemini no está disponible en tu país. Usa una key de Anthropic en Ajustes."
                e.httpCode == 403 -> "Tu key de Gemini no tiene permiso para este modelo."
                e.httpCode >= 500 -> "Gemini está saturado ahora. Intenta de nuevo en un momento."
                else -> "Gemini respondió con un error: ${e.message}"
            }
            is UnauthorizedException, is PermissionDeniedException -> "Tu API key de Anthropic no es válida. Revísala en Ajustes."
            is RateLimitException -> "Demasiadas solicitudes seguidas. Espera un momento e intenta de nuevo."
            is AnthropicServiceException -> "La IA no está disponible ahora (${e.statusCode()}). Intenta de nuevo."
            is IOException -> e.message ?: "Sin conexión. Revisa tu internet."
            else -> e.message ?: "Algo salió mal."
        }

        /** Errors where the right next step is adding or fixing a key in Settings. */
        fun needsSetup(e: Throwable) = e is MissingApiKeyException || e is FreeQuotaExceededException ||
            (e is GeminiApiException && (e.reason == "API_KEY_INVALID" || e.httpCode == 403)) ||
            e is UnauthorizedException || e is PermissionDeniedException
    }
}
