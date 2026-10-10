package com.folio.reader.data.dictionary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A headword the rules derive from an inflected word, with the part of speech the inflection implies (if any). */
data class Candidate(val word: String, val posHint: String? = null)

/**
 * English inflections, after WordNet's "morphy": strip a regular ending and try the base form. Irregular forms
 * (went, children) come from the dictionary's `forms` table instead.
 */
object EnglishMorphology {
    private val RULES = listOf(
        "noun" to listOf("s" to "", "ses" to "s", "xes" to "x", "zes" to "z", "ches" to "ch", "shes" to "sh", "men" to "man", "ies" to "y"),
        "verb" to listOf("s" to "", "ies" to "y", "es" to "e", "es" to "", "ed" to "e", "ed" to "", "ing" to "e", "ing" to ""),
        "adjective" to listOf("er" to "", "est" to "", "er" to "e", "est" to "e"),
    )

    fun candidates(word: String): List<Candidate> {
        val out = LinkedHashMap<String, Candidate>()
        for ((pos, rules) in RULES) for ((suffix, replacement) in rules) {
            if (word.endsWith(suffix) && word.length - suffix.length >= 2) {
                val base = word.dropLast(suffix.length) + replacement
                if (base != word) out.putIfAbsent(base, Candidate(base, pos))
                // Doubled final consonant: stopped -> stop, running -> run, bigger -> big.
                if (suffix in DOUBLING && base.length >= 3 && replacement.isEmpty() && base.last() == base[base.length - 2] && base.last() !in "aeiou") {
                    val undoubled = base.dropLast(1)
                    out.putIfAbsent(undoubled, Candidate(undoubled, pos))
                }
            }
        }
        return out.values.toList()
    }

    private val DOUBLING = setOf("ed", "ing", "er", "est")
}

/**
 * Spanish inflections from `assets/dictionary/es_rules.json`: verb endings for -ar/-er/-ir (with stem changes like
 * duermo -> dormir and the pronominal -se), attached pronouns (dígamelo), and plurals/feminines. The dictionary
 * builder (tools/dictionary/es_morphology.py) uses the same file and stores only forms these rules can't derive,
 * so this must produce the same candidates as the Python version (see SpanishMorphologyTest).
 */
class SpanishMorphology(rulesJson: String) {
    private val clitics: List<String>
    private val verbEndings: List<Pair<String, List<String>>>
    private val stemChanges: List<Pair<String, String>>
    private val nominal: List<Pair<String, String>>

    init {
        val rules = Json.parseToJsonElement(rulesJson).jsonObject
        clitics = rules.strings("clitics")
        verbEndings = rules.getValue("verb_endings").jsonObject.map { (inf, endings) -> inf to endings.jsonArray.map { it.jsonPrimitive.content } }
        stemChanges = rules.pairs("stem_changes")
        nominal = rules.pairs("nominal")
    }

    /** Every headword the rules can derive (not checked against the dictionary), closest first. */
    fun candidates(input: String): List<String> {
        val word = input.lowercase()
        val stems = mutableListOf(word)
        // Attached pronouns: "dígamelo" -> "díga" -> "diga" (the written accent only marks stress before a pronoun).
        repeat(2) {
            for (s in stems.toList()) for (clitic in clitics) {
                if (s.endsWith(clitic) && s.length - clitic.length >= 3) {
                    val base = s.dropLast(clitic.length)
                    for (b in listOf(base, unaccent(base))) if (b !in stems) stems += b
                }
            }
        }
        val out = mutableListOf<String>()
        for (s in stems) {
            for ((infinitive, endings) in verbEndings) for (ending in endings) {
                if (s.endsWith(ending) && s.length - ending.length >= 1) {
                    val stem = s.dropLast(ending.length)
                    val variants = mutableListOf(stem)
                    for ((old, new) in stemChanges) {
                        val i = stem.lastIndexOf(old)
                        if (i >= 0) variants += stem.substring(0, i) + new + stem.substring(i + old.length)
                    }
                    for (v in variants) if (v.isNotEmpty()) {
                        out += v + infinitive
                        out += v + infinitive + "se"
                    }
                }
            }
            for ((suffix, replacement) in nominal) {
                if (s.endsWith(suffix) && s.length - suffix.length >= 2) out += s.dropLast(suffix.length) + replacement
            }
        }
        val seen = HashSet<String>()
        return (stems.drop(1) + out).filter { it != word && seen.add(it) }
    }

    private fun unaccent(s: String) = s.map { UNACCENT[it] ?: it }.joinToString("")

    private companion object {
        val UNACCENT = mapOf('á' to 'a', 'é' to 'e', 'í' to 'i', 'ó' to 'o', 'ú' to 'u')

        fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }
        fun JsonObject.pairs(key: String) = getValue(key).jsonArray.map { p ->
            p.jsonArray.let { it[0].jsonPrimitive.content to it[1].jsonPrimitive.content }
        }
    }
}
