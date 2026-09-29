package com.folio.reader.ai

import kotlinx.serialization.Serializable

@Serializable
data class Encyclopedia(val entries: List<LoreEntry> = emptyList())

@Serializable
data class LoreEntry(
    val id: String,
    val name: String,
    /** lugar, objeto, termino, faccion, evento. */
    val kind: String = "termino",
    val aliases: List<String> = emptyList(),
    val description: String = "",
    val firstAppearance: String = "",
    val importance: Int = 3,
    val facts: List<String> = emptyList(),
    val related: List<String> = emptyList(),
)
