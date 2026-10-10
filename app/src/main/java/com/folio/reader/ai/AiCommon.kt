package com.folio.reader.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

data class ChatTurn(val fromUser: Boolean, val text: String)

sealed interface AiEvent {
    data class Delta(val text: String) : AiEvent
    data object Refused : AiEvent
    data object Truncated : AiEvent
}

/** No AI provider is configured yet. */
class MissingApiKeyException : Exception("Turn on the free AI with a Google Gemini key, or add your Anthropic key in Settings.")

/** The free provider's daily/minute quota ran out: the moment to suggest a personal key. */
class FreeQuotaExceededException : Exception(
    "You've reached Gemini's free limit for now. Wait a while (the daily limit resets every day) " +
        "or add your own Anthropic key in Settings to keep going without limits."
)

/** A provider that can answer spoiler-free questions and build the character map. */
interface AiBackend {
    /** Streams an answer grounded only in [context]; [history] holds earlier turns of this conversation. */
    fun ask(context: BookContext, selection: String?, history: List<ChatTurn>, question: String): Flow<AiEvent>

    /** Runs a JSON-schema extraction over the book text up to the reader's position; returns the raw JSON. */
    suspend fun extractStructured(context: BookContext, task: StructuredTask): String

    /** Short, cheap request without the book (e.g. define a word from its passage). */
    fun quick(system: String, user: String, maxTokens: Int): Flow<AiEvent>

    /** Cheap round-trip to validate the key. */
    suspend fun testConnection(): String
}

/** A spoiler-free, schema-constrained extraction over the book: the character map or the encyclopedia. */
enum class StructuredTask(
    val system: String,
    val schema: Map<String, Any>,
    private val verb: String,
    /** Extra instruction for slower local models, which would take many minutes to write the full-size result. */
    val compact: String,
) {
    GRAPH(
        Prompts.GRAPH_SYSTEM, Prompts.GRAPH_SCHEMA, "the character and relationship map",
        "Keep it compact: at most 20 characters and 30 relationships, each description one short sentence.",
    ),
    ENCYCLOPEDIA(
        Prompts.ENCYCLOPEDIA_SYSTEM, Prompts.ENCYCLOPEDIA_SCHEMA, "the book encyclopedia",
        "Keep it compact: at most 25 entries, at most 2 facts and 3 related names each, one-sentence descriptions. " +
            "Never include people or characters (not even the protagonist): only places, objects, terms, factions and events.",
    );

    // The reader's language goes here, not in the (cached) system prompt.
    fun instruction(context: BookContext) =
        "Build $verb up to where the reader is (${context.positionLabel}). " +
            "Write all human-readable text in ${Prompts.readerLanguage()}. Return only the JSON."
}

/** Prompts and schema shared by every provider, so the spoiler rules are identical whichever model answers. */
object Prompts {

    /** The reader's language (the device's), in English, e.g. "English" or "Spanish". */
    fun readerLanguage(): String = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" }

    fun bookHeader(context: BookContext) = "<book title=\"${context.bookTitle}\" author=\"${context.author}\">\n"

    fun firstQuestion(selection: String?, question: String) = buildString {
        if (!selection.isNullOrBlank()) {
            append("Passage I selected: \"")
            append(selection.trim().take(1_500))
            append("\"\n\n")
        }
        append("My question: ")
        append(question)
    }

    val DEFINE_SYSTEM = """
        You are the dictionary and translation helper inside an e-reader. The reader selected a word or short
        phrase in a book. Using the passage around it, explain what it means *here*.

        Reply in the reader's language (given in the request), plain text, very short (max ~80 words), in this
        shape, with the labels written in the reader's language:
        **<term>** — part of speech, if it's a single word
        Meaning here: 1–2 sentences about its meaning in this passage.
        Translation: only if the book's language differs from the reader's — the natural rendering of the term
        in the reader's language (for idioms or phrases, translate the whole expression).
        Other senses: one line, only if the word commonly means something quite different.

        Stick to the passage: don't discuss the plot or anything that might come later.
    """.trimIndent()

    fun defineRequest(bookTitle: String, language: String?, passage: String, term: String) = buildString {
        append("Book: ").append(bookTitle)
        if (!language.isNullOrBlank()) append(" (language: ").append(language).append(")")
        append("\nReader's language: ").append(readerLanguage())
        // Small models add "Translation: <same word>" anyway; say it outright when there's nothing to translate.
        val bookLang = language?.lowercase()?.take(2)
        if (bookLang != null && bookLang == Locale.getDefault().language.lowercase()) {
            append("\nThe book is in the reader's language: leave out the Translation line.")
        }
        append("\n\nPassage:\n\"").append(passage.trim()).append("\"\n\nSelected term: \"").append(term.trim()).append("\"")
    }

    val ASK_SYSTEM = """
        You are Lumen, the reading companion inside an e-reader app. The reader is partway through a book and
        has asked you something about it — usually about a passage they selected: a character, place, object,
        term, event or reference they can't place.

        You receive the book's text from the beginning up to exactly where the reader is (inside <book>). The
        text stops at the marker "[THE READER IS HERE ...]". That text is your ONLY source.

        Spoiler protection — these rules matter more than being helpful:
        - Use only what appears in the provided text. Do not use anything you may know about this book, its
          author, sequels, adaptations or commentary, even if you recognize the book.
        - Never reveal, hint at, foreshadow or speculate about anything beyond the reader's position: future
          events, hidden identities, twists, deaths, or how relationships evolve. If something is still open at
          this point, say it remains unresolved so far.
        - If the text doesn't answer the question, say so plainly instead of guessing.
        - Every detail you state must be in the text. Don't fill gaps with plausible-sounding details; a shorter
          answer is better than an invented one.
        - Often only the most recent pages are included, to save space, plus an <excerpts> block with earlier
          passages about what the reader asked: treat those passages as part of the book, and don't assume
          anything about what was left out. If they don't answer the question, say the passages you were given
          don't cover it.

        How to answer:
        - Lead with a one-sentence answer (who/what it is).
        - Then say where it first appeared (chapter number/title) and the key things the text has established so
          far, most relevant first. Short quotes from the text help the reader remember.
        - Keep it brief: about 60–180 words unless the reader asks for more. Plain text; **bold** for names is
          fine, and short "- " bullets when listing. No headings.
        - Reply in the language of the reader's question.
    """.trimIndent()

    val GRAPH_SYSTEM = """
        You build a character map for a reader who is partway through a book. You receive the book's text from the
        beginning up to the reader's position (inside <book>); it ends at "[THE READER IS HERE ...]".

        Spoiler protection: use only the provided text. Ignore anything you may know about this book from
        elsewhere. Describe characters and relationships as they stand at the reader's position — never mention
        later developments, hidden identities or future events.

        Output rules:
        - Include the characters that matter to the story so far (at most 45), ranked by importance 1–5
          (5 = protagonist). Skip one-off unnamed extras.
        - "id" is a short lowercase slug, unique. "aliases" are other names/titles used in the text.
        - "description": 1–2 sentences, spoiler-free.
        - "firstAppearance": the chapter where they first appear, e.g. "Chapter 3" or the chapter title.
        - "group": family, house, faction or place they belong to, or "" if none.
        - Relationships only between listed ids, as established in the text. "type" is one of: family, romance,
          friendship, alliance, rivalry, work, other. "label" is 1–3 words (e.g. "sister of", "mentor").
          "description" is one short sentence.
        - Write all human-readable text in the language the request asks for.
    """.trimIndent()

    val GRAPH_SCHEMA: Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "required" to listOf("characters", "relationships"),
        "properties" to mapOf(
            "characters" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "additionalProperties" to false,
                    "required" to listOf("id", "name", "aliases", "description", "firstAppearance", "importance", "group"),
                    "properties" to mapOf(
                        "id" to mapOf("type" to "string"),
                        "name" to mapOf("type" to "string"),
                        "aliases" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                        "description" to mapOf("type" to "string"),
                        "firstAppearance" to mapOf("type" to "string"),
                        "importance" to mapOf("type" to "integer"),
                        "group" to mapOf("type" to "string"),
                    ),
                ),
            ),
            "relationships" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "additionalProperties" to false,
                    "required" to listOf("source", "target", "type", "label", "description"),
                    "properties" to mapOf(
                        "source" to mapOf("type" to "string"),
                        "target" to mapOf("type" to "string"),
                        "type" to mapOf(
                            "type" to "string",
                            "enum" to RelationType.KEYS,
                        ),
                        "label" to mapOf("type" to "string"),
                        "description" to mapOf("type" to "string"),
                    ),
                ),
            ),
        ),
    )

    val ENCYCLOPEDIA_SYSTEM = """
        You build a small encyclopedia ("wiki") of a book for a reader who is partway through it. You receive the
        book's text from the beginning up to the reader's position (inside <book>); it ends at "[THE READER IS HERE ...]".

        Spoiler protection: use only the provided text. Ignore anything you may know about this book from
        elsewhere. Describe every entry only as it stands at the reader's position — never mention later
        developments, revelations or future events.

        What to include (NOT characters — they have their own map):
        - "place": places, cities, buildings, regions, worlds.
        - "object": notable objects, artifacts, documents, weapons.
        - "term": invented words, concepts, customs, titles, magic, technology, foreign or period terms.
        - "faction": groups, families as a group, organizations, institutions, orders.
        - "event": notable events referenced or that happened so far (wars, crimes, ceremonies).
        At most 60 entries, the ones that matter most first. "importance" 1–5 (5 = central to the story so far).

        Fields: "id" short lowercase slug; "aliases" other names used; "description" 1–2 sentences;
        "firstAppearance" chapter where it first appears (e.g. "Chapter 3" or the chapter title); "facts" up to 4
        short facts the text has established; "related" up to 5 names of characters or other entries it's
        connected to. Write all human-readable text in the language the request asks for.
    """.trimIndent()

    val ENCYCLOPEDIA_SCHEMA: Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "required" to listOf("entries"),
        "properties" to mapOf(
            "entries" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "additionalProperties" to false,
                    "required" to listOf("id", "name", "kind", "aliases", "description", "firstAppearance", "importance", "facts", "related"),
                    "properties" to mapOf(
                        "id" to mapOf("type" to "string"),
                        "name" to mapOf("type" to "string"),
                        "kind" to mapOf("type" to "string", "enum" to LoreKind.KEYS),
                        "aliases" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                        "description" to mapOf("type" to "string"),
                        "firstAppearance" to mapOf("type" to "string"),
                        "importance" to mapOf("type" to "integer"),
                        "facts" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                        "related" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    ),
                ),
            ),
        ),
    )

    /** Converts plain Kotlin maps/lists/primitives (like [GRAPH_SCHEMA]) to a JSON tree. */
    fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
        is List<*> -> JsonArray(value.map(::toJson))
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
}
