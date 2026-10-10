package com.folio.reader.ai

import com.folio.reader.ui.reader.SearchController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Answers to the first question of a conversation, per book and place in the book, so asking the same thing again
 * (same selection, same question, same 1% of the book) costs no tokens. A file per book in [dir]; the newest
 * [MAX_PER_BOOK] answers are kept.
 */
class AnswerCache(private val dir: File) {

    @Serializable
    private data class Entry(val key: String, val answer: String, val savedAt: Long)

    private val mutex = Mutex()

    suspend fun get(bookId: String, key: String): String? = mutex.withLock {
        withContext(Dispatchers.IO) { read(bookId).lastOrNull { it.key == key }?.answer }
    }

    suspend fun put(bookId: String, key: String, answer: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val entries = read(bookId).filter { it.key != key } + Entry(key, answer, System.currentTimeMillis())
            val file = fileFor(dir, bookId)
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(entries.takeLast(MAX_PER_BOOK)))
        }
    }

    private fun read(bookId: String): List<Entry> {
        val file = fileFor(dir, bookId)
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<Entry>>(file.readText()) }.getOrDefault(emptyList())
    }

    companion object {
        const val DIR = "ai-answers"
        private const val MAX_PER_BOOK = 100
        private val json = Json { ignoreUnknownKeys = true }

        /** [base] is the app's files dir (or a test folder). */
        fun fileFor(base: File, bookId: String) = File(base, "$DIR/$bookId.json")

        /** Same selection and question (ignoring case, accents and spacing) at the same 1% of the book. */
        fun key(selection: String?, question: String, progress: Float): String {
            fun norm(s: String?) = SearchController.fold(s.orEmpty()).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            return "${(progress * 100).toInt()}|${norm(selection)}|${norm(question)}"
        }
    }
}
