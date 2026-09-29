package com.folio.reader.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiRequestTest {

    private val context = BookContext(
        bookTitle = "One Hundred Years of Solitude",
        author = "Gabriel García Márquez",
        priorChapters = "<chapter n=\"1\">\nMany years later…\n</chapter>",
        currentChapter = "<chapter n=\"2\" status=\"in progress\">\nMelquíades came back.\n[THE READER IS HERE.]\n</chapter>",
        positionLabel = "II · 12% of the book",
        spineIndex = 1,
        progress = 0.12f,
    )

    @Test
    fun askBody_putsBookFirst_andAlternatesRoles() {
        val history = listOf(ChatTurn(true, "Who is he?"), ChatTurn(false, "He's the gypsy."))
        val body = GeminiService.askBody(context, "Melquíades", history, "And where does he come from?")

        assertTrue(body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("Spoiler protection"))
        val contents = body["contents"]!!.jsonArray
        assertEquals(listOf("user", "model", "user"), contents.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val firstParts = contents[0].jsonObject["parts"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
        assertTrue(firstParts[0].startsWith("<book title=\"One Hundred Years of Solitude\""))
        assertTrue(firstParts[0].contains("Many years later") && firstParts[0].endsWith("</book>"))
        assertTrue(firstParts[1].contains("\"Melquíades\"") && firstParts[1].contains("Who is he?"))
        assertEquals("low", body["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
    }

    @Test
    fun graphBody_requestsJsonSchema() {
        val cfg = GeminiService.graphBody(context)["generationConfig"]!!.jsonObject
        assertEquals("application/json", cfg["responseMimeType"]!!.jsonPrimitive.content)
        val schema = cfg["responseJsonSchema"]!!.jsonObject
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertEquals("false", schema["additionalProperties"]!!.jsonPrimitive.content)
        assertTrue(schema["properties"]!!.jsonObject.containsKey("relationships"))
    }

    @Test
    fun streamChunks_textSkipsThoughts_andReadsFinishReason() {
        val sse = """
            data: {"candidates":[{"content":{"role":"model","parts":[{"text":"thinking","thought":true},{"text":"It's **Melquíades**"}]}}]}

            data: {"candidates":[{"content":{"role":"model","parts":[{"text":", the gypsy."}]},"finishReason":"STOP"}]}

        """.trimIndent()
        val chunks = GeminiService.sseChunks(sse.reader().buffered()).toList()
        assertEquals("It's **Melquíades**, the gypsy.", chunks.joinToString("") { GeminiService.chunkText(it) })
        assertEquals("STOP", GeminiService.finishReason(chunks.last()))

        val blocked = Json.parseToJsonElement("""{"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"}}""").jsonObject
        assertEquals("SAFETY", GeminiService.finishReason(blocked))
    }

    @Test
    fun errors_quotaBecomesFreeQuotaExceeded_invalidKeyIsReadable() {
        val quota = GeminiService.parseError(429, """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}""")
        assertTrue(quota is FreeQuotaExceededException)
        assertTrue(AiService.needsSetup(quota))

        val bad = GeminiService.parseError(
            400,
            """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"@type":"x","reason":"API_KEY_INVALID"}]}}""",
        )
        assertEquals("Your Gemini key isn't valid. Check it in Settings.", AiService.friendlyError(bad))
        assertTrue(AiService.needsSetup(bad))
    }
}
