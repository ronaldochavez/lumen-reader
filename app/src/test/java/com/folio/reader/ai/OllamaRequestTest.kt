package com.folio.reader.ai

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaRequestTest {

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
    fun normalizeUrl_acceptsBareAddresses() {
        assertEquals("http://192.168.1.65:11434", OllamaService.normalizeUrl("192.168.1.65"))
        assertEquals("http://192.168.1.65:11434", OllamaService.normalizeUrl(" 192.168.1.65:11434/ "))
        assertEquals("http://pc.local:8080", OllamaService.normalizeUrl("http://pc.local:8080"))
        assertEquals("https://ai.example.com:11434", OllamaService.normalizeUrl("https://ai.example.com"))
    }

    @Test
    fun askMessages_bookFirst_thenAlternatingTurns() {
        val history = listOf(ChatTurn(true, "Who is he?"), ChatTurn(false, "He's the gypsy."))
        val messages = OllamaService.askMessages(context, "Melquíades", history, "And where does he come from?")
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it.first })
        val first = messages[1].second
        assertTrue(first.startsWith("<book title=\"One Hundred Years of Solitude\""))
        assertTrue(first.contains("Many years later") && first.contains("</book>") && first.contains("Who is he?"))
        assertEquals("And where does he come from?", messages.last().second)
    }

    @Test
    fun chatBody_disablesThinking_andSetsContextWindow() {
        val body = OllamaService.chatBody("qwen3.5:9b", listOf("user" to "hi"), stream = true, maxTokens = 64)
        assertEquals("qwen3.5:9b", body["model"]!!.jsonPrimitive.content)
        assertFalse(body["think"]!!.jsonPrimitive.boolean)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        val options = body["options"]!!.jsonObject
        assertEquals(OllamaService.NUM_CTX, options["num_ctx"]!!.jsonPrimitive.int)
        assertEquals(64, options["num_predict"]!!.jsonPrimitive.int)
        assertEquals("hi", body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("format"))
    }

    @Test
    fun ndjson_readsTextAndDoneReason_andSurfacesErrors() {
        val stream = """
            {"model":"qwen3.5:9b","message":{"role":"assistant","content":"It's"},"done":false}
            {"model":"qwen3.5:9b","message":{"role":"assistant","content":" the gypsy."},"done":false}
            {"model":"qwen3.5:9b","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop"}
        """.trimIndent()
        val chunks = OllamaService.ndjson(stream.reader().buffered()).toList()
        assertEquals("It's the gypsy.", chunks.joinToString("") { OllamaService.chunkText(it) })
        assertEquals("stop", OllamaService.doneReason(chunks.last()))

        val failed = runCatching { OllamaService.ndjson("""{"error":"out of memory"}""".reader().buffered()).toList() }
        assertTrue(failed.exceptionOrNull()?.message?.contains("out of memory") == true)
    }

    @Test
    fun missingModel_explainsHowToDownloadIt() {
        val e = OllamaService.parseError(404, """{"error":"model 'qwen3.5:9b' not found"}""", "qwen3.5:9b")
        assertEquals("The model qwen3.5:9b isn't downloaded on your computer. Run: ollama pull qwen3.5:9b", e.message)
    }

    @Test
    fun excerpts_keepFirstMention_ignoringAccents() {
        val filler = "Lorem ipsum dolor sit amet. ".repeat(60)
        val chapters = listOf(
            "Chapter 1" to filler + "Then MELQUIADES arrived with his magnets." + filler,
            "Chapter 2" to filler + "Melquíades returned, older." + filler,
            "Chapter 3" to filler + "Nobody mentions the gypsy here." + filler,
        )
        val out = SpoilerFreeContextBuilder.excerptsMentioning("Melquíades", chapters, budget = 5_000)
        assertTrue(out.startsWith("<excerpts") && out.endsWith("</excerpts>"))
        assertTrue("first appearance is included", out.contains("[Chapter 1]") && out.contains("MELQUIADES arrived"))
        assertTrue(out.contains("[Chapter 2]") && out.contains("Melquíades returned"))
        assertFalse(out.contains("[Chapter 3]"))
        assertEquals("", SpoilerFreeContextBuilder.excerptsMentioning("Aureliano", chapters, budget = 5_000))
    }
}
