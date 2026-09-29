package com.folio.reader.ai

import android.util.Log
import com.folio.reader.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** An error returned by the Gemini API (`{"error": {"code", "message", "status", "details": [{"reason"}]}}`). */
class GeminiApiException(val httpCode: Int, val status: String, val reason: String?, message: String) : IOException(message)

/**
 * Google Gemini on its free tier, with the user's own (free) AI Studio key. Talks to the REST
 * `models/{model}:streamGenerateContent?alt=sse` endpoint directly.
 */
class GeminiService(private val settings: SettingsRepository) : AiBackend {

    override fun ask(context: BookContext, selection: String?, history: List<ChatTurn>, question: String): Flow<AiEvent> = flow {
        val key = key()
        var finish: String? = null
        stream(key, askBody(context, selection, history, question)).use { reader ->
            for (chunk in sseChunks(reader)) {
                chunkText(chunk).takeIf { it.isNotEmpty() }?.let { emit(AiEvent.Delta(it)) }
                finishReason(chunk)?.let { finish = it }
            }
        }
        when (finish) {
            "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "RECITATION", "SPII", "OTHER" -> emit(AiEvent.Refused)
            "MAX_TOKENS" -> emit(AiEvent.Truncated)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun extractStructured(context: BookContext, task: StructuredTask): String = withContext(Dispatchers.IO) {
        val out = StringBuilder()
        var finish: String? = null
        stream(key(), structuredBody(context, task)).use { reader ->
            for (chunk in sseChunks(reader)) {
                out.append(chunkText(chunk))
                finishReason(chunk)?.let { finish = it }
            }
        }
        if (finish == "MAX_TOKENS") throw IOException("El resultado quedó incompleto; intenta de nuevo.")
        if (finish != null && finish != "STOP") throw IOException("La IA no pudo procesar este libro ($finish).")
        out.toString()
    }

    override fun quick(system: String, user: String, maxTokens: Int): Flow<AiEvent> = flow {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
            putJsonArray("contents") { add(content("user", user)) }
            putJsonObject("generationConfig") {
                put("maxOutputTokens", maxTokens)
                putJsonObject("thinkingConfig") { put("thinkingLevel", "low") }
            }
        }
        var finish: String? = null
        stream(key(), body).use { reader ->
            for (chunk in sseChunks(reader)) {
                chunkText(chunk).takeIf { it.isNotEmpty() }?.let { emit(AiEvent.Delta(it)) }
                finishReason(chunk)?.let { finish = it }
            }
        }
        if (finish != null && finish != "STOP" && finish != "MAX_TOKENS") emit(AiEvent.Refused)
    }.flowOn(Dispatchers.IO)

    override suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonArray("contents") { add(content("user", "Responde solo con: ¡Listo para leer contigo!")) }
            putJsonObject("generationConfig") { put("maxOutputTokens", 64); putJsonObject("thinkingConfig") { put("thinkingLevel", "low") } }
        }
        val text = stream(key(), body).use { r -> sseChunks(r).joinToString("") { chunkText(it) } }
        text.ifBlank { "Conexión correcta." }
    }

    private suspend fun key(): String = settings.geminiKey.first()?.takeIf { it.isNotBlank() } ?: throw MissingApiKeyException()

    /**
     * Opens the SSE stream, retrying transient 5xx answers (the free tier returns 503 "high demand" often) and
     * then falling back to the previous Flash model, which usually has spare capacity during those spikes.
     */
    private suspend fun stream(key: String, body: JsonObject): BufferedReader {
        val payload = body.toString().toByteArray(Charsets.UTF_8)
        for ((i, step) in ATTEMPTS.withIndex()) {
            val (model, wait) = step
            delay(wait)
            val conn = URL("$BASE/models/$model:streamGenerateContent?alt=sse").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 300_000 // big books + thinking can take a while before the first token
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            if (code in 200..299) return conn.inputStream.bufferedReader(Charsets.UTF_8)
            val raw = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            Log.w(TAG, "Gemini $model HTTP $code (attempt ${i + 1}): ${raw.take(500)}")
            if (code !in RETRYABLE || i == ATTEMPTS.lastIndex) throw parseError(code, raw)
        }
        error("unreachable")
    }

    companion object {
        const val MODEL = "gemini-3.8-flash"
        const val FALLBACK_MODEL = "gemini-3.7-flash"
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        const val API_KEY_URL = "https://aistudio.google.com/apikey"
        private const val TAG = "LumenAI"
        private val RETRYABLE = setOf(500, 502, 503, 504)
        /** Model and wait before each try: one quick retry, then the fallback model twice. */
        private val ATTEMPTS = listOf(MODEL to 0L, MODEL to 1_500L, FALLBACK_MODEL to 0L, FALLBACK_MODEL to 3_000L)

        /** ~200k tokens: stays under typical free-tier per-minute token limits. */
        const val MAX_CONTEXT_CHARS = 800_000

        private val json = Json { ignoreUnknownKeys = true }

        private fun content(role: String, vararg texts: String) = buildJsonObject {
            put("role", role)
            putJsonArray("parts") { texts.forEach { t -> add(buildJsonObject { put("text", t) }) } }
        }

        private fun bookText(context: BookContext) =
            Prompts.bookHeader(context) + context.priorChapters.let { if (it.isBlank()) "" else it + "\n\n" } +
                context.currentChapter + "\n</libro>"

        /** The book goes first so Gemini's implicit prefix caching can reuse it across follow-up questions. */
        internal fun askBody(context: BookContext, selection: String?, history: List<ChatTurn>, question: String): JsonObject =
            buildJsonObject {
                putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", Prompts.ASK_SYSTEM) }) } }
                putJsonArray("contents") {
                    val turns = history + ChatTurn(fromUser = true, text = question)
                    turns.forEachIndexed { i, turn ->
                        when {
                            i == 0 -> add(content("user", bookText(context), Prompts.firstQuestion(selection, turn.text)))
                            turn.fromUser -> add(content("user", turn.text))
                            else -> add(content("model", turn.text.ifBlank { "…" }))
                        }
                    }
                }
                putJsonObject("generationConfig") {
                    put("maxOutputTokens", 8192)
                    putJsonObject("thinkingConfig") { put("thinkingLevel", "low") }
                }
            }

        internal fun graphBody(context: BookContext): JsonObject = structuredBody(context, StructuredTask.GRAPH)

        internal fun structuredBody(context: BookContext, task: StructuredTask): JsonObject = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", task.system) }) } }
            putJsonArray("contents") { add(content("user", bookText(context), task.instruction(context))) }
            putJsonObject("generationConfig") {
                put("maxOutputTokens", 32768)
                put("responseMimeType", "application/json")
                put("responseJsonSchema", Prompts.toJson(task.schema))
                putJsonObject("thinkingConfig") { put("thinkingLevel", "medium") }
            }
        }

        /** `data: {...}` lines of a server-sent-events stream, parsed. */
        internal fun sseChunks(reader: BufferedReader): Sequence<JsonObject> = reader.lineSequence()
            .filter { it.startsWith("data:") }
            .mapNotNull { runCatching { json.parseToJsonElement(it.removePrefix("data:").trim()).jsonObject }.getOrNull() }

        /** Visible text of a chunk (thought-summary parts are skipped). */
        internal fun chunkText(chunk: JsonObject): String {
            val parts = chunk["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject?.get("parts")?.jsonArray ?: return ""
            return parts.joinToString("") { p ->
                val o = p.jsonObject
                if (o["thought"]?.jsonPrimitive?.content == "true") "" else o["text"]?.jsonPrimitive?.content.orEmpty()
            }
        }

        internal fun finishReason(chunk: JsonObject): String? =
            chunk["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.content?.let { "SAFETY" }
                ?: chunk["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("finishReason")?.jsonPrimitive?.content

        internal fun parseError(code: Int, raw: String): Exception {
            val err = runCatching { json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject }.getOrNull()
            val status = err?.get("status")?.jsonPrimitive?.content.orEmpty()
            val message = err?.get("message")?.jsonPrimitive?.content ?: "Error $code"
            val reason = err?.get("details")?.jsonArray
                ?.firstNotNullOfOrNull { it.jsonObject["reason"]?.jsonPrimitive?.content }
            if (code == 429 || status == "RESOURCE_EXHAUSTED") return FreeQuotaExceededException()
            return GeminiApiException(code, status, reason, message)
        }
    }
}
