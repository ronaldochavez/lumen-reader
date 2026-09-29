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
    /** One of [RelationType.KEYS]; maps saved before the English keys may hold the Spanish ones. */
    val type: String = RelationType.OTHER,
    val label: String = "",
    val description: String = "",
) {
    val kind: String get() = RelationType.canonical(type)
}

object RelationType {
    const val FAMILY = "family"
    const val ROMANCE = "romance"
    const val FRIENDSHIP = "friendship"
    const val ALLIANCE = "alliance"
    const val RIVALRY = "rivalry"
    const val WORK = "work"
    const val OTHER = "other"
    val KEYS = listOf(FAMILY, ROMANCE, FRIENDSHIP, ALLIANCE, RIVALRY, WORK, OTHER)

    /** Keys used by maps generated while the app was in Spanish. */
    private val LEGACY = mapOf(
        "familia" to FAMILY, "pareja" to ROMANCE, "amistad" to FRIENDSHIP, "alianza" to ALLIANCE,
        "rivalidad" to RIVALRY, "trabajo" to WORK, "otro" to OTHER,
    )

    fun canonical(type: String) = LEGACY[type] ?: type.takeIf { it in KEYS } ?: OTHER
}
