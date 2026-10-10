package com.folio.reader.data.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class DictSense(val pos: String, val definition: String, val examples: List<String>)

/** One headword and its senses, most common first. [inflectedFrom] is the selected word when it differs (winced). */
data class DictEntry(val headword: String, val senses: List<DictSense>, val inflectedFrom: String? = null)

data class DictionaryResult(val query: String, val language: String, val entries: List<DictEntry>, val attribution: String)

/**
 * Offline dictionaries bundled with the app (built by tools/dictionary/build_dictionary.py):
 * English from Open English WordNet, Spanish from Spanish Wiktionary. No network and no AI credits.
 */
class DictionaryRepository(private val context: Context) {

    private val mutex = Mutex()
    private val open = HashMap<String, SQLiteDatabase>()
    private val spanish by lazy {
        SpanishMorphology(context.assets.open("dictionary/es_rules.json").bufferedReader().use { it.readText() })
    }

    /**
     * Looks the selection up in the book's language first, then the other dictionary. Returns null when it's not a
     * word or short phrase, or no dictionary knows it.
     */
    suspend fun lookup(selection: String, bookLanguage: String?): DictionaryResult? = withContext(Dispatchers.IO) {
        val query = normalize(selection) ?: return@withContext null
        val preferred = bookLanguage?.lowercase()?.take(2)
        val order = if (preferred == "es") listOf("es", "en") else listOf("en", "es")
        for (lang in order) {
            val entries = runCatching { find(lang, query) }.getOrDefault(emptyList())
            if (entries.isNotEmpty()) return@withContext DictionaryResult(query, lang, entries, ATTRIBUTION.getValue(lang))
        }
        null
    }

    private suspend fun find(lang: String, word: String): List<DictEntry> {
        val db = database(lang)
        // Headwords to try, closest first: the word itself, its stored irregular forms, then what the rules derive.
        val ruleCandidates: List<Candidate> = if (lang == "en") EnglishMorphology.candidates(word)
            else spanish.candidates(word).map { Candidate(it) }
        val irregular = db.rawQuery("SELECT word FROM forms WHERE form = ?", arrayOf(word)).use { c ->
            buildList { while (c.moveToNext()) add(Candidate(c.getString(0))) }
        }
        val tried = LinkedHashMap<String, Candidate>()
        (listOf(Candidate(word)) + irregular + ruleCandidates).forEach { tried.putIfAbsent(it.word, it) }

        val senses = HashMap<String, MutableList<DictSense>>()
        tried.keys.chunked(400).forEach { batch ->
            val sql = "SELECT s.word, g.pos, g.definition, g.examples FROM senses s JOIN glosses g ON g.id = s.gloss " +
                "WHERE s.word IN (${batch.joinToString(",") { "?" }}) ORDER BY s.rank"
            db.rawQuery(sql, batch.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val examples = c.getString(3).split('\n').filter(String::isNotBlank)
                    senses.getOrPut(c.getString(0)) { mutableListOf() } += DictSense(c.getString(1), c.getString(2), examples)
                }
            }
        }
        return tried.values.filter { it.word in senses }.take(MAX_HEADWORDS).map { cand ->
            val list = senses.getValue(cand.word)
            // "winced" came through a verb ending: show the verb senses first.
            val ordered = cand.posHint?.let { hint -> list.sortedBy { if (it.pos == hint) 0 else 1 } } ?: list
            DictEntry(cand.word, ordered, inflectedFrom = word.takeIf { it != cand.word })
        }
    }

    /** Opens the bundled database, copying it out of the APK the first time (or after an app update). */
    private suspend fun database(lang: String): SQLiteDatabase = mutex.withLock {
        open[lang]?.let { return it }
        val dir = File(context.filesDir, "dictionary").apply { mkdirs() }
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val file = File(dir, "$lang-$version.db")
        if (!file.exists()) {
            dir.listFiles { f -> f.name.startsWith("$lang-") }?.forEach { it.delete() } // older copies
            val tmp = File(dir, "$lang.tmp")
            context.assets.open("dictionary/$lang.db").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(file)
        }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            .also { open[lang] = it }
    }

    companion object {
        private const val MAX_HEADWORDS = 3
        private const val MAX_WORDS = 4

        val ATTRIBUTION = mapOf(
            "en" to "Open English WordNet (CC BY 4.0), based on Princeton WordNet",
            "es" to "Wiktionary en español (CC BY-SA 4.0)",
        )

        private val EDGE = Regex("^[\\p{P}\\p{S}\\s]+|[\\p{P}\\p{S}\\s]+$")

        /** "“Raskolnikov’s,”" -> "raskolnikov". Null for empty selections or anything longer than a short phrase. */
        fun normalize(selection: String): String? {
            var s = selection.replace('’', '\'').replace(Regex("\\s+"), " ").trim()
            s = s.replace(EDGE, "")
            if (s.endsWith("'s") || s.endsWith("s'")) s = s.dropLast(if (s.endsWith("'s")) 2 else 1)
            s = s.lowercase()
            if (s.isEmpty() || s.split(' ').size > MAX_WORDS || s.none { it.isLetter() }) return null
            return s
        }
    }
}
