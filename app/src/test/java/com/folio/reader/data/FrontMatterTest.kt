package com.folio.reader.data

import com.folio.reader.ai.SpoilerFreeContextBuilder
import com.folio.reader.data.db.TocEntity
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontMatterTest {

    private fun toc(vararg entries: Pair<String, Int>) = entries.mapIndexed { i, (title, spine) ->
        TocEntity(bookId = "b", ord = i, title = title, depth = 0, spineIndex = spine, fragment = "f$i")
    }

    /** A classics edition (modeled on a Barnes & Noble one): teaser quotes, introduction and more before chapter one. */
    @Test
    fun classicsEdition_storyStartsAtChapterOne() {
        val t = toc(
            "FROM THE PAGES OF CRIME AND PUNISHMENT" to 1, "Title Page" to 1, "Copyright Page" to 1,
            "FYODOR DOSTOEVSKY" to 1, "THE WORLD OF FYODOR DOSTOEVSKY AND CRIME AND PUNISHMENT" to 1,
            "Introduction" to 1, "LIST OF CHARACTERS" to 1, "RASKOLNIKOV’S PETERSBURG" to 1,
            "CHAPTER ONE" to 1, "CHAPTER TWO" to 1, "CHAPTER SIX" to 2,
        )
        assertEquals("CHAPTER ONE", FrontMatter.storyStartEntry(t, 8)?.title)
    }

    /** Title page, dedication and epigraph don't tell the plot: nothing is left out. */
    @Test
    fun ordinaryFrontMatter_isKept() {
        val t = toc("Cover" to 0, "Title Page" to 1, "Copyright" to 2, "Dedication" to 3, "Epigraph" to 4, "Chapter One" to 5)
        assertNull(FrontMatter.storyStartEntry(t, 30))
        val dcc = toc("Title Page" to 1, "Copyright" to 2, "Dedication" to 3, "Epigraph" to 4, "Chapter 1" to 5, "Part I" to 6)
        assertNull(FrontMatter.storyStartEntry(dcc, 60))
    }

    @Test
    fun spanishEdition_andPrologue() {
        val t = toc("Portada" to 0, "Introducción" to 1, "Nota del traductor" to 2, "Prólogo" to 3, "Capítulo I" to 4)
        assertEquals("Prólogo", FrontMatter.storyStartEntry(t, 40)?.title)
        val libro = toc("Estudio preliminar" to 1, "Libro primero" to 2)
        assertEquals("Libro primero", FrontMatter.storyStartEntry(libro, 20)?.title)
    }

    /** Unnumbered chapter titles: the story starts right after the last introduction-like entry near the start. */
    @Test
    fun unnumberedChapters_startAfterTheIntroduction() {
        val t = toc("Cover" to 0, "Foreword" to 1, "Introduction" to 2, "The Boy Who Lived" to 3, "The Vanishing Glass" to 4)
        assertEquals("The Boy Who Lived", FrontMatter.storyStartEntry(t, 20)?.title)
        // An afterword at the end isn't front matter.
        val end = toc("The Boy Who Lived" to 1, "The Vanishing Glass" to 2, "Afterword" to 19)
        assertNull(FrontMatter.storyStartEntry(end, 20))
    }

    @Test
    fun titles() {
        assertTrue(FrontMatter.isStoryStart("PART ONE"))
        assertTrue(FrontMatter.isStoryStart("Chapter 1: Arrival"))
        assertTrue(FrontMatter.isStoryStart("1"))
        assertTrue(FrontMatter.isStoryStart("I."))
        assertFalse(FrontMatter.isStoryStart("I Am Legend"))
        assertFalse(FrontMatter.isStoryStart("1984"))
        assertFalse(FrontMatter.isStoryStart("Section 3"))
        assertTrue(FrontMatter.isSpoilerProne("Introduction by Joseph Frank"))
        assertTrue(FrontMatter.isSpoilerProne("A Note on the Translation"))
        assertTrue(FrontMatter.isSpoilerProne("Translator’s Preface"))
        assertTrue(FrontMatter.isSpoilerProne("A Chronology of Dostoevsky’s Life"))
        assertFalse(FrontMatter.isSpoilerProne("List of Characters"))
        assertFalse(FrontMatter.isSpoilerProne("The Introduction of Mr. Darcy"))
    }

    /** The cut point is found in the same text the importer extracts, inside a file that mixes front matter and story. */
    @Test
    fun offsetOfFragment_inExtractedText() {
        val html = """<html><body>
            <h2>Introduction</h2><p>The murderer is revealed at the end.</p>
            <h2><a id="filepos83573"></a>CHAPTER ONE</h2><p>On an exceptionally hot evening early in July…</p>
            </body></html>"""
        val doc = Jsoup.parse(html)
        val text = BookRepository.normalizeText(Jsoup.parse(html).body().wholeText())
        val offset = FrontMatter.offsetIn(doc, "filepos83573")!!
        assertTrue(text.substring(offset).startsWith("CHAPTER ONE"))

        val start = FrontMatter.StoryStart(spineIndex = 1, offset = offset)
        val story = SpoilerFreeContextBuilder.storyText(start, 1, text)
        assertFalse(story.contains("murderer"))
        assertTrue(story.startsWith("CHAPTER ONE"))
        assertEquals("", SpoilerFreeContextBuilder.storyText(start, 0, "Cover"))
        assertEquals("Later", SpoilerFreeContextBuilder.storyText(start, 2, "Later"))
        assertEquals("Anything", SpoilerFreeContextBuilder.storyText(null, 0, "Anything"))
    }
}
