package com.folio.reader.data.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MorphologyTest {

    private val spanish = SpanishMorphology(File("src/main/assets/dictionary/es_rules.json").readText())

    /**
     * The dictionary builder stores only the forms its Python rules can't derive, so the app's rules must derive
     * exactly the same candidates, or those words become unfindable. Fixture made by es_morphology.py.
     */
    @Test
    fun spanish_matchesTheDictionaryBuilderExactly() {
        val lines = javaClass.getResource("/dictionary/es_morphology_fixture.tsv")!!.readText()
            .lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(lines.size > 400)
        for (line in lines) {
            val (form, expected) = line.split('\t').let { it[0] to it.getOrElse(1) { "" } }
            assertEquals("candidates for '$form'", expected, spanish.candidates(form).joinToString("|"))
        }
    }

    @Test
    fun spanish_findsHeadwordsOfCommonForms() {
        mapOf(
            "hablaba" to "hablar", "comiendo" to "comer", "hablándole" to "hablar", "duermo" to "dormir",
            // (sigo -> seguir needs two stem changes: the builder stores it as an irregular form instead.)
            "busqué" to "buscar", "conozco" to "conocer", "luces" to "luz",
            "canciones" to "canción", "bonitas" to "bonito",
        ).forEach { (form, headword) -> assertTrue("$form -> $headword", headword in spanish.candidates(form)) }
    }

    @Test
    fun english_stripsRegularEndings() {
        fun bases(w: String) = EnglishMorphology.candidates(w).map { it.word }
        assertTrue("wince" in bases("winced"))
        assertTrue("run" in bases("running"))
        assertTrue("stop" in bases("stopped"))
        assertTrue("city" in bases("cities"))
        assertTrue("box" in bases("boxes"))
        assertTrue("big" in bases("biggest"))
        assertEquals("verb", EnglishMorphology.candidates("winced").first { it.word == "wince" }.posHint)
    }

    @Test
    fun normalize_cleansSelections() {
        assertEquals("raskolnikov", DictionaryRepository.normalize("“Raskolnikov’s,”"))
        assertEquals("winced", DictionaryRepository.normalize(" winced. "))
        assertEquals("qué", DictionaryRepository.normalize("¿Qué?"))
        assertEquals("give up", DictionaryRepository.normalize("give   up"))
        assertNull(DictionaryRepository.normalize("…"))
        assertNull(DictionaryRepository.normalize("this is far too long for a dictionary"))
    }
}
