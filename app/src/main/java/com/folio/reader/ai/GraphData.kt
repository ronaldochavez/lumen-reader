package com.folio.reader.ai

import kotlinx.serialization.Serializable

@Serializable
data class GraphData(
    val characters: List<GraphCharacter> = emptyList(),
    val relationships: List<GraphRelation> = emptyList(),
)

@Serializable
data class GraphCharacter(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val description: String = "",
    val firstAppearance: String = "",
    val importance: Int = 3,
    val group: String = "",
)

@Serializable
data class GraphRelation(
    val source: String,
    val target: String,
    val type: String = "otro",
    val label: String = "",
    val description: String = "",
)
