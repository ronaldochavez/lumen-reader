package com.folio.reader.ui.graph

import com.folio.reader.ai.GraphCharacter
import com.folio.reader.ai.GraphData
import com.folio.reader.ai.GraphRelation
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ForceLayoutTest {

    /** A crowded cast: a connected core plus minor characters the model listed without any relationship. */
    private fun crowdedGraph(): GraphData {
        val chars = (0 until 40).map { i ->
            GraphCharacter(id = "c$i", name = "Character $i", importance = when {
                i < 3 -> 5
                i < 10 -> 4
                i < 25 -> 3
                else -> 1
            })
        }
        val rels = buildList {
            for (i in 1 until 25) add(GraphRelation("c${i % 3}", "c$i"))       // everyone in the core knows a protagonist
            for (i in 3 until 24 step 2) add(GraphRelation("c$i", "c${i + 1}"))
        }
        return GraphData(chars, rels)                                          // c25..c39 have no relationships
    }

    @Test
    fun layout_keepsIsolatedCharactersNearTheRest() {
        val pos = ForceLayout.compute(crowdedGraph())
        val cx = pos.values.map { it.x }.average().toFloat()
        val cy = pos.values.map { it.y }.average().toFloat()
        val dist = pos.values.map { hypot(it.x - cx, it.y - cy) }.sorted()
        val median = dist[dist.size / 2]
        assertTrue("farthest node is ${dist.last() / median}x the median distance", dist.last() <= 2.2f * median)
    }

    /** Circles are drawn with ForceLayout.nodeRadius (up to 46 units): they and their names must not overlap. */
    @Test
    fun layout_circlesDoNotOverlap() {
        val g = crowdedGraph()
        val pos = ForceLayout.compute(g)
        val r = g.characters.associate { it.id to ForceLayout.nodeRadius(it.importance) }
        val ids = pos.keys.toList()
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val (a, b) = ids[i] to ids[j]
            val d = hypot(pos.getValue(a).x - pos.getValue(b).x, pos.getValue(a).y - pos.getValue(b).y)
            assertTrue("$a and $b are $d apart, circles need ${r.getValue(a) + r.getValue(b)}", d >= r.getValue(a) + r.getValue(b) + 30f)
        }
    }

    @Test
    fun layout_protagonistsAreCloserToTheCentreThanExtras() {
        val g = crowdedGraph()
        val pos = ForceLayout.compute(g)
        val cx = pos.values.map { it.x }.average().toFloat()
        val cy = pos.values.map { it.y }.average().toFloat()
        fun avgDist(ids: List<String>) = ids.map { hypot(pos.getValue(it).x - cx, pos.getValue(it).y - cy) }.average()
        val core = avgDist(listOf("c0", "c1", "c2"))
        val extras = avgDist((25 until 40).map { "c$it" })
        assertTrue("protagonists $core vs extras $extras", core < extras)
    }
}
