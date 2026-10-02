package com.folio.reader.ai

import com.folio.reader.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** The reader's computer (Ollama) can't be reached right now: off, asleep, or on another network. */
class LocalAiUnavailableException(url: String) :
    IOException("Couldn't reach Ollama at $url. Is your computer on and on the same Wi-Fi?")

/**
 * A model running on the reader's own computer through Ollama (e.g. Qwen): free, unlimited and private. Its
 * context window is much smaller than Gemini's or Claude's, so it receives a trimmed book context (see
 * [MAX_CONTEXT_CHARS] and [SpoilerFreeContextBuilder]).
 */
class OllamaService(private val settings: SettingsRepository) : AiBackend {

    override fun ask(context: BookContext, selection: String?, history: List<ChatTurn>, question: String): Flow<AiEvent> = flow {
        var reason: String? = null
        stream(chatBody(model(), askMessages(context, selection, history, question), stream = true)).use { reader ->
            for (chunk in ndjson(reader)) {
                chunkText(chunk).takeIf { it.isNotEmpty() }?.let { emit(AiEvent.Delta(it)) }
                doneReason(chunk)?.let { reason = it }
            }
        }
        if (reason == "length") emit(AiEvent.Truncated)
    }.flowOn(Dispatchers.IO)

    override suspend fun extractStructured(context: BookContext, task: StructuredTask): String = withContext(Dispatchers.IO) {
        val messages = listOf(
            "system" to task.system,
            "user" to Prompts.bookHeader(context) + bookBody(context) + "\n\n" + task.instruction(context),
        )
        val body = chatBody(model(), messages, stream = false, format = Prompts.toJson(task.schema) as JsonObject)
        val response = stream(body).use { json.parseToJsonElement(it.readText()).jsonObject }
        if (doneReason(response) == "length") throw IOException("The result came back incomplete; try again.")
        chunkText(response).ifBlank { throw IOException("The local AI returned an empty answer.") }
    }

    override fun quick(system: String, user: String, maxTokens: Int): Flow<AiEvent> = flow {
        stream(chatBody(model(), listOf("system" to system, "user" to user), stream = true, maxTokens = maxTokens)).use { reader ->
            for (chunk in ndjson(reader)) chunkText(chunk).takeIf { it.isNotEmpty() }?.let { emit(AiEvent.Delta(it)) }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val model = model()
        val installed = installedModels()
        if (installed.none { it == model || it == "$model:latest" }) {
            throw IOException("The model $model isn't downloaded on your computer. Run: ollama pull $model")
        }
        val reply = stream(chatBody(model, listOf("user" to "Reply only with: Ready to read with you!"), stream = false, maxTokens = 64))
            .use { chunkText(json.parseToJsonElement(it.readText()).jsonObject) }
        "$model · ${reply.trim().ifBlank { "Connection OK." }}"
    }

    /** Quick check (short timeout) used to decide whether to fall back to a cloud provider. */
    suspend fun isReachable(): Boolean = withContext(Dispatchers.IO) {
        val base = baseUrl() ?: return@withContext false
        runCatching {
            val conn = URL("$base/api/version").openConnection() as HttpURLConnection
            conn.connectTimeout = REACH_TIMEOUT_MS
            conn.readTimeout = REACH_TIMEOUT_MS
            try { conn.responseCode in 200..299 } finally { conn.disconnect() }
        }.getOrDefault(false)
    }

    /** Model names Ollama has downloaded, e.g. "qwen3.5:9b". */
    suspend fun installedModels(): List<String> = withContext(Dispatchers.IO) {
        val base = baseUrl() ?: throw LocalAiUnavailableException("(no address set)")
        val conn = open("$base/api/tags", "GET")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Ollama answered HTTP $code")
            val tags = json.parseToJsonElement(conn.inputStream.bufferedReader().readText()).jsonObject
            tags["models"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }.orEmpty()
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun baseUrl(): String? = settings.localAiUrl.first()?.let(::normalizeUrl)

    private suspend fun model(): String = settings.localAiModel.first()

    private fun open(url: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        return conn
    }

    private suspend fun stream(body: JsonObject): BufferedReader {
        val base = baseUrl() ?: throw LocalAiUnavailableException("(no address set)")
        val conn = open("$base/api/chat", "POST")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) return conn.inputStream.bufferedReader(Charsets.UTF_8)
            val raw = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            throw parseError(code, raw, model())
        } catch (e: IOException) {
            if (e is ConnectException || e is NoRouteToHostException || e is UnknownHostException ||
                (e is SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) == true)
            ) throw LocalAiUnavailableException(base)
            throw e
        }
    }

    companion object {
        /**
         * Book text per request. Ollama runs with a 32K-token window (OLLAMA_CONTEXT_LENGTH / num_ctx): ~70K chars
         * is ~20K tokens, leaving room for the prompt and a long answer (the character map's JSON).
         */
        const val MAX_CONTEXT_CHARS = 70_000
        const val NUM_CTX = 32_768
        const val DEFAULT_PORT = 11434
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 300_000 // the first request also loads the model into the GPU
        private const val REACH_TIMEOUT_MS = 1_500

        private val json = Json { ignoreUnknownKeys = true }

        /** Accepts "192.168.1.65", "192.168.1.65:11434" or a full URL; returns "http://host:port" without a slash. */
        fun normalizeUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
            val hostPart = url.substringAfter("://").substringBefore('/')
            if (!hostPart.contains(':')) url = url.replaceFirst(hostPart, "$hostPart:$DEFAULT_PORT")
            return url
        }

        private fun bookBody(context: BookContext) =
            context.priorChapters.let { if (it.isBlank()) "" else it + "\n\n" } + context.currentChapter + "\n</book>"

        internal fun askMessages(context: BookContext, selection: String?, history: List<ChatTurn>, question: String): List<Pair<String, String>> {
            val turns = history + ChatTurn(fromUser = true, text = question)
            return listOf("system" to Prompts.ASK_SYSTEM) + turns.mapIndexed { i, turn ->
                when {
                    i == 0 -> "user" to Prompts.bookHeader(context) + bookBody(context) + "\n\n" + Prompts.firstQuestion(selection, turn.text)
                    turn.fromUser -> "user" to turn.text
                    else -> "assistant" to turn.text.ifBlank { "…" }
                }
            }
        }

        /** Thinking is off: on a home GPU it would double the wait for little gain on these questions. */
        internal fun chatBody(
            model: String,
            messages: List<Pair<String, String>>,
            stream: Boolean,
            format: JsonObject? = null,
            maxTokens: Int? = null,
        ): JsonObject = buildJsonObject {
            put("model", model)
            put("stream", stream)
            put("think", false)
            putJsonArray("messages") {
                messages.forEach { (role, content) -> add(buildJsonObject { put("role", role); put("content", content) }) }
            }
            format?.let { put("format", it) }
            putJsonObject("options") {
                put("num_ctx", NUM_CTX)
                maxTokens?.let { put("num_predict", it) }
            }
        }

        /** Ollama streams one JSON object per line. */
        internal fun ndjson(reader: BufferedReader): Sequence<JsonObject> = reader.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            .onEach { chunk -> chunk["error"]?.jsonPrimitive?.content?.let { throw IOException("Ollama: $it") } }

        internal fun chunkText(chunk: JsonObject): String =
            chunk["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content.orEmpty()

        internal fun doneReason(chunk: JsonObject): String? =
            if (chunk["done"]?.jsonPrimitive?.boolean == true) chunk["done_reason"]?.jsonPrimitive?.content else null

        internal fun parseError(code: Int, raw: String, model: String): IOException {
            val message = runCatching { json.parseToJsonElement(raw).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
            return when {
                code == 404 && message?.contains("not found") == true ->
                    IOException("The model $model isn't downloaded on your computer. Run: ollama pull $model")
                message != null -> IOException("Ollama: $message")
                else -> IOException("Ollama answered HTTP $code")
            }
        }
    }
}
