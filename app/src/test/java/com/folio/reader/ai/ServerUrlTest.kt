package com.folio.reader.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerUrlTest {

    @Test
    fun plainAddresses_getSchemeAndPort() {
        assertEquals("http://192.168.1.65:8880", normalizeServerUrl("192.168.1.65", 8880))
        assertEquals("http://192.168.1.65:9000", normalizeServerUrl(" 192.168.1.65:9000/ ", 8880))
        assertEquals("https://pc.local:443", normalizeServerUrl("https://pc.local:443", 8880))
        assertEquals("http://httpbox.local:11434", normalizeServerUrl("httpbox.local", 11434))
    }

    /** What phone keyboards produce: the scheme is replaced, never doubled ("http://http:/…" was saved in Settings). */
    @Test
    fun mangledSchemes_areReplaced() {
        assertEquals("http://192.168.1.65:11434", normalizeServerUrl("http:/192.168.1.65:11434", 8880))
        assertEquals("http://192.168.1.65:8880", normalizeServerUrl("Http://192.168.1.65", 8880))
        assertEquals("http://192.168.1.65:8880", normalizeServerUrl("http//192.168.1.65", 8880))
        assertEquals("http://192.168.1.65:11434", normalizeServerUrl("http://http:/192.168.1.65:11434", 8880))
        assertEquals("https://192.168.1.65:8880", normalizeServerUrl("HTTPS://192.168.1.65", 8880))
    }

    @Test
    fun ollama_usesItsPort() {
        assertEquals("http://192.168.1.65:11434", OllamaService.normalizeUrl("192.168.1.65"))
        assertEquals("http://192.168.1.65:11434", OllamaService.normalizeUrl("http:/192.168.1.65"))
    }
}
