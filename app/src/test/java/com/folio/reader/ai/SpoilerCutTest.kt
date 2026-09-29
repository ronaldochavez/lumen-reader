package com.folio.reader.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpoilerCutTest {
    private val text = "First sentence. Second sentence with a secret! Third sentence that reveals the killer."

    @Test
    fun cutAtFraction_backsUpToSentenceEnd_soUnreadWordsNeverLeak() {
        // Cut lands mid-way through the third sentence.
        val cut = SpoilerFreeContextBuilder.cutAtFraction(text, 0.8f)
        assertEquals("First sentence. Second sentence with a secret!", cut)
        assertFalse(cut.contains("killer"))
    }

    @Test
    fun cutAtFraction_edges() {
        assertEquals("", SpoilerFreeContextBuilder.cutAtFraction(text, 0f))
        assertEquals(text, SpoilerFreeContextBuilder.cutAtFraction(text, 1f))
        assertEquals("", SpoilerFreeContextBuilder.cutAtFraction("", 0.5f))
    }
}
