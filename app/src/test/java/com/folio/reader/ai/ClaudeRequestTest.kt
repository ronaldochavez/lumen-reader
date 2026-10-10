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

/** Checks the wire shape of the requests Lumen sends, without calling the API. */
class ClaudeRequestTest {

    private val context = BookContext(
        bookTitle = "One Hundred Years of Solitude",
        author = "Gabriel García Márquez",
        priorChapters = "<chapter n=\"1\" title=\"I\">\nMany years later…\n</chapter>",
        currentChapter = "<chapter n=\"2\" title=\"II\" status=\"in progress\">\nMelquíades came back.\n[THE READER IS HERE.]\n</chapter>",
        positionLabel = "II · 12% of the book",
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
        val history = listOf(ChatTurn(true, "Who is he?"), ChatTurn(false, "He's the gypsy."))
        val p = ClaudeService.askParams(AiModel.OPUS, context, "Melquíades", history, "And where does he come from?")
        val json = body(p)

        assertEquals("claude-opus-5-5", json["model"].asText())
        assertEquals("1h", json["system"][0]["cache_control"]["ttl"].asText())

        val messages = json["messages"]
        assertEquals(listOf("user", "assistant", "user"), messages.map { it["role"].asText() })

        val first = messages[0]["content"]
        assertEquals(3, first.size())
        assertTrue(first[0]["text"].asText().startsWith("<book title=\"One Hundred Years of Solitude\""))
        assertEquals("ephemeral", first[0]["cache_control"]["type"].asText())
        assertTrue(first[1]["text"].asText().endsWith("</book>"))
        assertEquals("ephemeral", first[1]["cache_control"]["type"].asText())
        assertNull(first[2]["cache_control"])
        assertTrue(first[2]["text"].asText().contains("\"Melquíades\""))
        assertTrue(first[2]["text"].asText().contains("Who is he?"))
        assertEquals("And where does he come from?", messages[2]["content"].asText())

        assertEquals("medium", json["output_config"]["effort"].asText())
        assertEquals("default", json["fallbacks"].asText())
        assertEquals(listOf("server-side-fallback-2026-07-01"), p._headers().values("anthropic-beta"))
        assertFalse(json.has("thinking"))
    }

    @Test
    fun askRequest_onHaiku_omitsEffortAndFallbacks() {
        val json = body(ClaudeService.askParams(AiModel.HAIKU, context, null, emptyList(), "Summary"))
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
