package com.folio.reader.data

import com.folio.reader.data.db.TocEntity
import kotlinx.serialization.Serializable
import org.jsoup.nodes.Document
import org.jsoup.nodes.TextNode

/** Where a contents entry starts: a spine item and a character offset in its extracted text. */
@Serializable
data class TocPosition(val spineIndex: Int, val offset: Int, val title: String, val depth: Int = 0)

/**
 * Which chapter a position is in. A spine item (an XHTML file) often holds many chapters (Gutenberg puts a whole
 * novel in one; some editions a whole Part per file), so the file's first contents entry doesn't say where the
 * reader is: the contents entries' positions inside the text do.
 */
object TocPositions {

    /** Positions of [entries] (all in the spine item whose HTML is [doc]), in the importer's text of that HTML. */
    fun inDocument(doc: Document, entries: List<TocEntity>): List<TocPosition> {
        val text = BookRepository.normalizeText(doc.body().wholeText())
        val marked = doc.clone()
        val ids = mutableListOf<TocEntity>()
        entries.forEach { e ->
            val fragment = e.fragment?.takeIf { it.isNotBlank() } ?: return@forEach
            val target = marked.getElementById(fragment) ?: marked.getElementsByAttributeValue("name", fragment).firstOrNull()
                ?: return@forEach
            target.before(TextNode("$MARK${ids.size}$END"))
            ids += e
        }
        val starts = entries.filter { it.fragment.isNullOrBlank() }.map { TocPosition(it.spineIndex, 0, it.title, it.depth) }
        if (ids.isEmpty()) return starts
        // The importer's own extraction with the markers in, lined up with the plain text: markers can add a line
        // break or keep a space from being trimmed, so whitespace may differ, but nothing else does.
        val withMarks = BookRepository.normalizeText(marked.body().wholeText())
        return starts + align(withMarks, text).map { (id, offset) -> ids[id].let { TocPosition(it.spineIndex, offset, it.title, it.depth) } }
    }

    /** Marker id → offset in [plain] of the first non-blank character after it in [withMarks]. */
    private fun align(withMarks: String, plain: String): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        val pending = mutableListOf<Int>()
        var i = 0
        var j = 0
        while (i < withMarks.length) {
            val c = withMarks[i]
            when {
                c == MARK[0] -> {
                    val end = withMarks.indexOf(END, i)
                    pending += withMarks.substring(i + 1, end).toInt()
                    i = end + 1
                }
                j < plain.length && c == plain[j] -> {
                    if (!c.isWhitespace()) { pending.forEach { out += it to j }; pending.clear() }
                    i++; j++
                }
                c.isWhitespace() -> i++
                j < plain.length && plain[j].isWhitespace() -> j++
                else -> { i++; j++ } // shouldn't happen; keep going
            }
        }
        pending.forEach { out += it to plain.length }
        return out
    }

    /**
     * The contents entry a position falls in, as a label: "CHAPTER TWO", "PART ONE · CHAPTER TWO" when the contents
     * nest chapters in parts. A spine item that starts mid-chapter continues the previous item's last entry.
     * [positions] must be sorted by spine item and offset.
     */
    fun labelAt(positions: List<TocPosition>, spineIndex: Int, offset: Int): String? {
        val i = positions.indexOfLast { it.spineIndex < spineIndex || (it.spineIndex == spineIndex && it.offset <= offset) }
        return if (i < 0) null else labelOf(positions, i)
    }

    private fun labelOf(positions: List<TocPosition>, i: Int): String {
        val entry = positions[i]
        val parent = if (entry.depth > 0) positions.subList(0, i).lastOrNull { it.depth < entry.depth } else null
        return listOfNotNull(parent?.title, entry.title).joinToString(" · ")
    }

    /**
     * True when several entries have the same label (every Part has a "CHAPTER SIX" and the contents don't list the
     * parts): the label alone then doesn't say which one.
     */
    fun labelsRepeat(positions: List<TocPosition>): Boolean {
        val labels = positions.indices.map { labelOf(positions, it).trim().lowercase() }
        return labels.size != labels.toSet().size
    }

    private const val MARK = ""
    private const val END = ""
}
