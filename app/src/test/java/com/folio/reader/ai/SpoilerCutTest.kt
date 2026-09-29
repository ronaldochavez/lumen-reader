package com.folio.reader.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpoilerCutTest {
    private val text = "Primera frase. Segunda frase con un secreto! Tercera frase que revela al asesino."

    @Test
    fun cutAtFraction_backsUpToSentenceEnd_soUnreadWordsNeverLeak() {
        // Cut lands mid-way through the third sentence.
        val cut = SpoilerFreeContextBuilder.cutAtFraction(text, 0.8f)
        assertEquals("Primera frase. Segunda frase con un secreto!", cut)
        assertFalse(cut.contains("asesino"))
    }

    @Test
    fun cutAtFraction_edges() {
        assertEquals("", SpoilerFreeContextBuilder.cutAtFraction(text, 0f))
        assertEquals(text, SpoilerFreeContextBuilder.cutAtFraction(text, 1f))
        assertEquals("", SpoilerFreeContextBuilder.cutAtFraction("", 0.5f))
    }
}
