package com.folio.reader.ai

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.MessageCreateParams
import com.fasterxml.jackson.databind.JsonNode
import com.folio.reader.data.AiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the wire shape of the requests Folio sends, without calling the API. */
class ClaudeRequestTest {

    private val context = BookContext(
        bookTitle = "Cien años de soledad",
        author = "Gabriel García Márquez",
        priorChapters = "<capitulo n=\"1\" titulo=\"I\">\nMuchos años después…\n</capitulo>",
        currentChapter = "<capitulo n=\"2\" titulo=\"II\" estado=\"en curso\">\nMelquíades volvió.\n[EL LECTOR VA AQUÍ.]\n</capitulo>",
        positionLabel = "II · 12% del libro",
        spineIndex = 1,
        progress = 0.12f,
    )

    private fun body(p: MessageCreateParams): JsonNode {
        val mapper = jsonMapper()
        val node = mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(p._body())
        p._additionalBodyProperties().forEach { (k, v) -> node.set<JsonNode>(k, mapper.valueToTree(v)) }
        return node
    }

    @Test
    fun askRequest_cachesBookText_andKeepsConversationOrder() {
        val history = listOf(ChatTurn(true, "¿Quién es?"), ChatTurn(false, "Es el gitano."))
        val p = ClaudeService.askParams(AiModel.OPUS, context, "Melquíades", history, "¿Y de dónde viene?")
        val json = body(p)

        assertEquals("claude-opus-5-5", json["model"].asText())
        assertEquals("1h", json["system"][0]["cache_control"]["ttl"].asText())

        val messages = json["messages"]
        assertEquals(listOf("user", "assistant", "user"), messages.map { it["role"].asText() })

        val first = messages[0]["content"]
        assertEquals(3, first.size())
        assertTrue(first[0]["text"].asText().startsWith("<libro titulo=\"Cien años de soledad\""))
        assertEquals("ephemeral", first[0]["cache_control"]["type"].asText())
        assertTrue(first[1]["text"].asText().endsWith("</libro>"))
        assertEquals("ephemeral", first[1]["cache_control"]["type"].asText())
        assertNull(first[2]["cache_control"])
        assertTrue(first[2]["text"].asText().contains("«Melquíades»"))
        assertTrue(first[2]["text"].asText().contains("¿Quién es?"))
        assertEquals("¿Y de dónde viene?", messages[2]["content"].asText())

        assertEquals("medium", json["output_config"]["effort"].asText())
        assertEquals("default", json["fallbacks"].asText())
        assertEquals(listOf("server-side-fallback-2026-07-01"), p._headers().values("anthropic-beta"))
        assertFalse(json.has("thinking"))
    }

    @Test
    fun askRequest_onHaiku_omitsEffortAndFallbacks() {
        val json = body(ClaudeService.askParams(AiModel.HAIKU, context, null, emptyList(), "Resumen"))
        assertFalse(json.has("output_config"))
        assertFalse(json.has("fallbacks"))
        // No prior chapters split: a single cached block + the question.
        assertEquals(3, json["messages"][0]["content"].size())
    }

    @Test
    fun graphRequest_usesJsonSchemaOutput() {
        val json = body(ClaudeService.graphParams(AiModel.SONNET, context))
        val format = json["output_config"]["format"]
        assertEquals("json_schema", format["type"].asText())
        assertEquals("object", format["schema"]["type"].asText())
        assertEquals(false, format["schema"]["additionalProperties"].asBoolean())
        assertTrue(format["schema"]["properties"].has("relationships"))
        assertEquals("high", json["output_config"]["effort"].asText())
        assertEquals("claude-sonnet-5-5", json["model"].asText())
    }
}
