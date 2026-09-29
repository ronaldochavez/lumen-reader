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
        bookTitle = "Cien años de soledad",
        author = "Gabriel García Márquez",
        priorChapters = "<capitulo n=\"1\">\nMuchos años después…\n</capitulo>",
        currentChapter = "<capitulo n=\"2\" estado=\"en curso\">\nMelquíades volvió.\n[EL LECTOR VA AQUÍ.]\n</capitulo>",
        positionLabel = "II · 12% del libro",
        spineIndex = 1,
        progress = 0.12f,
    )

    @Test
    fun askBody_putsBookFirst_andAlternatesRoles() {
        val history = listOf(ChatTurn(true, "¿Quién es?"), ChatTurn(false, "Es el gitano."))
        val body = GeminiService.askBody(context, "Melquíades", history, "¿Y de dónde viene?")

        assertTrue(body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("Spoiler protection"))
        val contents = body["contents"]!!.jsonArray
        assertEquals(listOf("user", "model", "user"), contents.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val firstParts = contents[0].jsonObject["parts"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
        assertTrue(firstParts[0].startsWith("<libro titulo=\"Cien años de soledad\""))
        assertTrue(firstParts[0].contains("Muchos años después") && firstParts[0].endsWith("</libro>"))
        assertTrue(firstParts[1].contains("«Melquíades»") && firstParts[1].contains("¿Quién es?"))
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
            data: {"candidates":[{"content":{"role":"model","parts":[{"text":"pensando","thought":true},{"text":"Es **Melquíades**"}]}}]}

            data: {"candidates":[{"content":{"role":"model","parts":[{"text":", el gitano."}]},"finishReason":"STOP"}]}

        """.trimIndent()
        val chunks = GeminiService.sseChunks(sse.reader().buffered()).toList()
        assertEquals("Es **Melquíades**, el gitano.", chunks.joinToString("") { GeminiService.chunkText(it) })
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
        assertEquals("Tu key de Gemini no es válida. Revísala en Ajustes.", AiService.friendlyError(bad))
        assertTrue(AiService.needsSetup(bad))
    }
}
