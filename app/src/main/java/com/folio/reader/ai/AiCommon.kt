package com.folio.reader.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ChatTurn(val fromUser: Boolean, val text: String)

sealed interface AiEvent {
    data class Delta(val text: String) : AiEvent
    data object Refused : AiEvent
    data object Truncated : AiEvent
}

/** No AI provider is configured yet. */
class MissingApiKeyException : Exception("Activa la IA gratis con una key de Google Gemini, o agrega tu key de Anthropic en Ajustes.")

/** The free provider's daily/minute quota ran out: the moment to suggest a personal key. */
class FreeQuotaExceededException : Exception(
    "Llegaste al límite gratis de Gemini por ahora. Espera un rato (el límite diario se reinicia cada día) " +
        "o agrega tu propia key de Anthropic en Ajustes para seguir sin límites."
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
enum class StructuredTask(val system: String, val schema: Map<String, Any>, private val verb: String) {
    GRAPH(Prompts.GRAPH_SYSTEM, Prompts.GRAPH_SCHEMA, "el mapa de personajes y relaciones"),
    ENCYCLOPEDIA(Prompts.ENCYCLOPEDIA_SYSTEM, Prompts.ENCYCLOPEDIA_SCHEMA, "la enciclopedia del libro");

    fun instruction(context: BookContext) =
        "Construye $verb hasta donde va el lector (${context.positionLabel}). Devuelve solo el JSON."
}

/** Prompts and schema shared by every provider, so the spoiler rules are identical whichever model answers. */
object Prompts {

    fun bookHeader(context: BookContext) = "<libro titulo=\"${context.bookTitle}\" autor=\"${context.author}\">\n"

    fun firstQuestion(selection: String?, question: String) = buildString {
        if (!selection.isNullOrBlank()) {
            append("Pasaje que seleccioné: «")
            append(selection.trim().take(1_500))
            append("»\n\n")
        }
        append("Mi pregunta: ")
        append(question)
    }

    val DEFINE_SYSTEM = """
        You are the dictionary and translation helper inside an e-reader. The reader selected a word or short
        phrase in a book. Using the passage around it, explain what it means *here*.

        Reply in Spanish, plain text, very short (max ~80 words), in this shape:
        **<term>** — part of speech, if it's a single word
        Significado aquí: 1–2 sentences about its meaning in this passage.
        Traducción: only if the book's text is not Spanish — the natural Spanish rendering of the term here
        (for idioms or phrases, translate the whole expression).
        Otras acepciones: one line, only if the word commonly means something quite different.

        Stick to the passage: don't discuss the plot or anything that might come later.
    """.trimIndent()

    fun defineRequest(bookTitle: String, language: String?, passage: String, term: String) = buildString {
        append("Libro: ").append(bookTitle)
        if (!language.isNullOrBlank()) append(" (idioma: ").append(language).append(")")
        append("\n\nPasaje:\n«").append(passage.trim()).append("»\n\nTérmino seleccionado: «").append(term.trim()).append("»")
    }

    fun graphInstruction(context: BookContext) =
        "Construye el mapa de personajes y relaciones hasta donde va el lector (${context.positionLabel}). Devuelve solo el JSON."

    val ASK_SYSTEM = """
        You are Lumen, the reading companion inside an e-reader app. The reader is partway through a book and
        has asked you something about it — usually about a passage they selected: a character, place, object,
        term, event or reference they can't place.

        You receive the book's text from the beginning up to exactly where the reader is (inside <libro>). The
        text stops at the marker "[EL LECTOR VA AQUÍ ...]". That text is your ONLY source.

        Spoiler protection — these rules matter more than being helpful:
        - Use only what appears in the provided text. Do not use anything you may know about this book, its
          author, sequels, adaptations or commentary, even if you recognize the book.
        - Never reveal, hint at, foreshadow or speculate about anything beyond the reader's position: future
          events, hidden identities, twists, deaths, or how relationships evolve. If something is still open at
          this point, say it remains unresolved "hasta donde vas".
        - If the text doesn't answer the question, say so plainly instead of guessing.

        How to answer:
        - Lead with a one-sentence answer (who/what it is).
        - Then say where it first appeared (chapter number/title) and the key things the text has established so
          far, most relevant first. Short quotes from the text help the reader remember.
        - Keep it brief: about 60–180 words unless the reader asks for more. Plain text; **bold** for names is
          fine, and short "- " bullets when listing. No headings.
        - Reply in the language of the reader's question (Spanish unless they write otherwise).
    """.trimIndent()

    val GRAPH_SYSTEM = """
        You build a character map for a reader who is partway through a book. You receive the book's text from the
        beginning up to the reader's position (inside <libro>); it ends at "[EL LECTOR VA AQUÍ ...]".

        Spoiler protection: use only the provided text. Ignore anything you may know about this book from
        elsewhere. Describe characters and relationships as they stand at the reader's position — never mention
        later developments, hidden identities or future events.

        Output rules:
        - Include the characters that matter to the story so far (at most 45), ranked by importance 1–5
          (5 = protagonist). Skip one-off unnamed extras.
        - "id" is a short lowercase slug, unique. "aliases" are other names/titles used in the text.
        - "description": 1–2 sentences, spoiler-free, in Spanish.
        - "firstAppearance": the chapter where they first appear, e.g. "Capítulo 3" or the chapter title.
        - "group": family, house, faction or place they belong to, or "" if none.
        - Relationships only between listed ids, as established in the text. "type" is one of: familia, pareja,
          amistad, alianza, rivalidad, trabajo, otro. "label" is 1–3 words in Spanish (e.g. "hermana de",
          "mentor"). "description" is one short sentence.
        - Write all human-readable text in Spanish.
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
                            "enum" to listOf("familia", "pareja", "amistad", "alianza", "rivalidad", "trabajo", "otro"),
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
        book's text from the beginning up to the reader's position (inside <libro>); it ends at "[EL LECTOR VA AQUÍ ...]".

        Spoiler protection: use only the provided text. Ignore anything you may know about this book from
        elsewhere. Describe every entry only as it stands at the reader's position — never mention later
        developments, revelations or future events.

        What to include (NOT characters — they have their own map):
        - "lugar": places, cities, buildings, regions, worlds.
        - "objeto": notable objects, artifacts, documents, weapons.
        - "termino": invented words, concepts, customs, titles, magic, technology, foreign or period terms.
        - "faccion": groups, families as a group, organizations, institutions, orders.
        - "evento": notable events referenced or that happened so far (wars, crimes, ceremonies).
        At most 60 entries, the ones that matter most first. "importance" 1–5 (5 = central to the story so far).

        Fields: "id" short lowercase slug; "aliases" other names used; "description" 1–2 sentences in Spanish;
        "firstAppearance" chapter where it first appears (e.g. "Capítulo 3" or the chapter title); "facts" up to 4
        short facts the text has established, in Spanish; "related" up to 5 names of characters or other entries
        it's connected to. Write all human-readable text in Spanish.
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
                        "kind" to mapOf("type" to "string", "enum" to listOf("lugar", "objeto", "termino", "faccion", "evento")),
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
