package com.folio.reader.ai

import kotlinx.serialization.Serializable

@Serializable
data class Encyclopedia(val entries: List<LoreEntry> = emptyList())

@Serializable
data class LoreEntry(
    val id: String,
    val name: String,
    /** One of [LoreKind.KEYS]; encyclopedias saved before the English keys may hold the Spanish ones. */
    val kind: String = LoreKind.TERM,
    val aliases: List<String> = emptyList(),
    val description: String = "",
    val firstAppearance: String = "",
    val importance: Int = 3,
    val facts: List<String> = emptyList(),
    val related: List<String> = emptyList(),
) {
    val kindKey: String get() = LoreKind.canonical(kind)
}

object LoreKind {
    const val PLACE = "place"
    const val OBJECT = "object"
    const val TERM = "term"
    const val FACTION = "faction"
    const val EVENT = "event"
    val KEYS = listOf(PLACE, OBJECT, TERM, FACTION, EVENT)

    /** Keys used by encyclopedias generated while the app was in Spanish. */
    private val LEGACY = mapOf("lugar" to PLACE, "objeto" to OBJECT, "termino" to TERM, "faccion" to FACTION, "evento" to EVENT)

    fun canonical(kind: String) = LEGACY[kind] ?: kind.takeIf { it in KEYS } ?: TERM
}
