package com.folio.reader.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.folio.reader.data.AiModel
import com.folio.reader.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException

/** Claude via the user's own Anthropic API key (the "personal" tier). */
class ClaudeService(private val settings: SettingsRepository) : AiBackend {

    private var cachedClient: Pair<String, AnthropicClient>? = null

    private suspend fun client(): AnthropicClient {
        val key = settings.apiKey.first()?.takeIf { it.isNotBlank() } ?: throw MissingApiKeyException()
        cachedClient?.let { (k, c) -> if (k == key) return c }
        return AnthropicOkHttpClient.builder().apiKey(key).build().also { cachedClient = key to it }
    }

    override fun ask(context: BookContext, selection: String?, history: List<ChatTurn>, question: String): Flow<AiEvent> = flow {
        val client = client()
        val model = settings.model.first()

        var stop: StopReason? = null
        client.messages().createStreaming(askParams(model, context, selection, history, question)).use { response ->
            val events = response.stream().iterator()
            while (events.hasNext()) {
                val event = events.next()
                val delta = event.contentBlockDelta().orElse(null)?.delta()?.text()?.orElse(null)?.text()
                if (!delta.isNullOrEmpty()) emit(AiEvent.Delta(delta))
                event.messageDelta().orElse(null)?.delta()?.stopReason()?.orElse(null)?.let { stop = it }
            }
        }
        when (stop) {
            StopReason.REFUSAL -> emit(AiEvent.Refused)
            StopReason.MAX_TOKENS -> emit(AiEvent.Truncated)
            else -> Unit
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun extractStructured(context: BookContext, task: StructuredTask): String = withContext(Dispatchers.IO) {
        val client = client()
        val model = settings.model.first()

        val out = StringBuilder()
        var stop: StopReason? = null
        client.messages().createStreaming(structuredParams(model, context, task)).use { response ->
            response.stream().forEach { event ->
                event.contentBlockDelta().orElse(null)?.delta()?.text()?.orElse(null)?.text()?.let(out::append)
                event.messageDelta().orElse(null)?.delta()?.stopReason()?.orElse(null)?.let { stop = it }
            }
        }
        if (stop == StopReason.REFUSAL) throw IOException("The AI couldn't process this book.")
        if (stop == StopReason.MAX_TOKENS) throw IOException("The result came back incomplete; try again.")
        out.toString()
    }

    override fun quick(system: String, user: String, maxTokens: Int): Flow<AiEvent> = flow {
        val client = client()
        val model = settings.model.first()
        val params = baseParams(model, maxTokens.toLong(), effort = OutputConfig.Effort.LOW)
            .system(system)
            .addUserMessage(user)
            .build()
        var stop: StopReason? = null
        client.messages().createStreaming(params).use { response ->
            val events = response.stream().iterator()
            while (events.hasNext()) {
                val event = events.next()
                event.contentBlockDelta().orElse(null)?.delta()?.text()?.orElse(null)?.text()
                    ?.takeIf { it.isNotEmpty() }?.let { emit(AiEvent.Delta(it)) }
                event.messageDelta().orElse(null)?.delta()?.stopReason()?.orElse(null)?.let { stop = it }
            }
        }
        if (stop == StopReason.REFUSAL) emit(AiEvent.Refused)
    }.flowOn(Dispatchers.IO)

    override suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val model = settings.model.first()
        val params = MessageCreateParams.builder()
            .model(model.id)
            .maxTokens(64L)
            .addUserMessage("Reply only with: Ready to read with you!")
            .build()
        client().messages().create(params).content()
            .firstNotNullOfOrNull { it.text().orElse(null)?.text() }
            ?: "Connection OK."
    }

    companion object {
        internal val graphJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

        /** Request for a question about the book; [history] holds earlier turns of the same conversation. */
        internal fun askParams(
            model: AiModel,
            context: BookContext,
            selection: String?,
            history: List<ChatTurn>,
            question: String,
        ): MessageCreateParams {
            val builder = baseParams(model, maxTokens = 8_000, effort = OutputConfig.Effort.MEDIUM)
                .systemOfTextBlockParams(listOf(cached(Prompts.ASK_SYSTEM)))
            val turns = history + ChatTurn(fromUser = true, text = question)
            turns.forEachIndexed { i, turn ->
                when {
                    i == 0 -> builder.addUserMessageOfBlockParams(bookBlocks(context) + text(Prompts.firstQuestion(selection, turn.text)))
                    turn.fromUser -> builder.addUserMessage(turn.text)
                    else -> builder.addAssistantMessage(turn.text.ifBlank { "…" })
                }
            }
            return builder.build()
        }

        internal fun graphParams(model: AiModel, context: BookContext): MessageCreateParams =
            structuredParams(model, context, StructuredTask.GRAPH)

        internal fun structuredParams(model: AiModel, context: BookContext, task: StructuredTask): MessageCreateParams =
            baseParams(model, maxTokens = 32_000, effort = OutputConfig.Effort.HIGH, jsonSchema = task.schema)
                .systemOfTextBlockParams(listOf(cached(task.system)))
                .addUserMessageOfBlockParams(bookBlocks(context) + text(task.instruction(context)))
                .build()

        private fun baseParams(
            model: AiModel,
            maxTokens: Long,
            effort: OutputConfig.Effort,
            jsonSchema: Map<String, Any>? = null,
        ): MessageCreateParams.Builder {
            val b = MessageCreateParams.builder().model(model.id).maxTokens(maxTokens)
            // Haiku 4.5 has no effort parameter; the 5.x models do.
            if (model != AiModel.HAIKU || jsonSchema != null) {
                val cfg = OutputConfig.builder()
                if (model != AiModel.HAIKU) cfg.effort(effort)
                if (jsonSchema != null) {
                    val schema = JsonOutputFormat.Schema.builder()
                    jsonSchema.forEach { (k, v) -> schema.putAdditionalProperty(k, JsonValue.from(v)) }
                    cfg.format(JsonOutputFormat.builder().schema(schema.build()).build())
                }
                b.outputConfig(cfg.build())
            }
            // If a safety classifier declines, let the API re-run the request on its recommended fallback model.
            if (model == AiModel.OPUS || model == AiModel.SONNET) {
                b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
            return b
        }

        /** Book text blocks. Stable parts carry cache breakpoints so follow-up questions reuse them. */
        private fun bookBlocks(context: BookContext): List<ContentBlockParam> {
            val header = Prompts.bookHeader(context)
            val blocks = mutableListOf<ContentBlockParam>()
            if (context.priorChapters.isNotBlank()) {
                blocks += ContentBlockParam.ofText(cached(header + context.priorChapters))
                blocks += ContentBlockParam.ofText(cached(context.currentChapter + "\n</book>"))
            } else {
                blocks += ContentBlockParam.ofText(cached(header + context.currentChapter + "\n</book>"))
            }
            return blocks
        }

        private fun cached(text: String): TextBlockParam = TextBlockParam.builder()
            .text(text)
            .cacheControl(CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build())
            .build()

        private fun text(t: String) = ContentBlockParam.ofText(TextBlockParam.builder().text(t).build())
    }
}
