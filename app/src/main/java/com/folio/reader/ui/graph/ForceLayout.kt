package com.folio.reader.ui.graph

import androidx.compose.ui.geometry.Offset
import com.folio.reader.ai.GraphData
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Fruchterman–Reingold layout. Important characters start near the centre and get a gentle pull towards it,
 * so protagonists end up in the middle and minor characters orbit around them.
 *
 * Kept compact on purpose: repulsion only acts between nearby nodes, characters without relationships are pulled in
 * harder (otherwise nothing holds them and they drift to the edges), and any stragglers are brought back to the
 * outer ring at the end. A single far-away node would otherwise make "fit to screen" shrink everyone else.
 */
object ForceLayout {

    fun compute(graph: GraphData, size: Float = 1000f, iterations: Int = 400): Map<String, Offset> {
        val nodes = graph.characters.map { it.id }
        if (nodes.isEmpty()) return emptyMap()
        val importance = graph.characters.associate { it.id to it.importance.coerceIn(1, 5) }
        val index = nodes.withIndex().associate { it.value to it.index }
        val edges = graph.relationships.mapNotNull { r ->
            val a = index[r.source] ?: return@mapNotNull null
            val b = index[r.target] ?: return@mapNotNull null
            if (a == b) null else a to b
        }.distinct()

        val n = nodes.size
        val degree = IntArray(n).also { d -> edges.forEach { (a, b) -> d[a]++; d[b]++ } }
        val rnd = Random(42)
        val x = FloatArray(n)
        val y = FloatArray(n)
        val center = size / 2
        nodes.forEachIndexed { i, id ->
            val ring = (5 - (importance[id] ?: 3)) / 4f // 0 for protagonists .. 1 for extras
            val radius = size * (0.08f + 0.36f * ring) + rnd.nextFloat() * 30f
            val angle = (2 * PI * i / n).toFloat() + rnd.nextFloat() * 0.3f
            x[i] = center + radius * cos(angle)
            y[i] = center + radius * sin(angle)
        }

        val k = sqrt(size * size / n) * 0.62f
        val cutoff = 3 * k
        val gravity = FloatArray(n) { i ->
            val base = 0.02f + 0.03f * (importance[nodes[i]] ?: 3)
            if (degree[i] == 0) base + 0.12f else base
        }
        var temperature = size / 8f
        val dx = FloatArray(n)
        val dy = FloatArray(n)

        fun step(repulsionRange: Float, maxMove: Float, withEdges: Boolean) {
            dx.fill(0f); dy.fill(0f)
            for (i in 0 until n) for (j in i + 1 until n) {
                var ddx = x[i] - x[j]
                var ddy = y[i] - y[j]
                var dist = sqrt(ddx * ddx + ddy * ddy)
                if (dist > repulsionRange) continue
                if (dist < 0.01f) { ddx = rnd.nextFloat(); ddy = rnd.nextFloat(); dist = 0.5f }
                val force = k * k / dist
                val fx = ddx / dist * force
                val fy = ddy / dist * force
                dx[i] += fx; dy[i] += fy
                dx[j] -= fx; dy[j] -= fy
            }
            if (withEdges) for ((a, b) in edges) {
                val ddx = x[a] - x[b]
                val ddy = y[a] - y[b]
                val dist = max(sqrt(ddx * ddx + ddy * ddy), 0.01f)
                val force = dist * dist / k
                val fx = ddx / dist * force
                val fy = ddy / dist * force
                dx[a] -= fx; dy[a] -= fy
                dx[b] += fx; dy[b] += fy
            }
            for (i in 0 until n) {
                dx[i] += (center - x[i]) * gravity[i]
                dy[i] += (center - y[i]) * gravity[i]
                val len = max(sqrt(dx[i] * dx[i] + dy[i] * dy[i]), 0.01f)
                x[i] += dx[i] / len * min(len, maxMove)
                y[i] += dy[i] / len * min(len, maxMove)
            }
        }

        repeat(iterations) {
            step(cutoff, temperature, withEdges = true)
            temperature = max(temperature * 0.985f, 1.5f)
        }
        pullInStragglers(x, y)
        // Settle after moving stragglers: only short-range repulsion, so nodes don't overlap.
        repeat(60) { step(k, 4f, withEdges = true) }
        separate(x, y, FloatArray(n) { nodeRadius(importance[nodes[it]] ?: 3) })
        return nodes.withIndex().associate { (i, id) -> id to Offset(x[i], y[i]) }
    }

    /** Radius a node is drawn with (CharacterGraphScreen), in layout units. */
    fun nodeRadius(importance: Int) = 16f + importance.coerceIn(1, 5) * 6f

    /** Room for the name drawn under each circle. */
    private const val LABEL_GAP = 46f

    /**
     * Pushes apart any two nodes whose circles (plus room for their names) overlap. Gravity packs the protagonists
     * into the middle, and they're also the biggest circles, so without this the centre of the map is a pile-up.
     */
    internal fun separate(x: FloatArray, y: FloatArray, radius: FloatArray, rounds: Int = 120) {
        val n = x.size
        repeat(rounds) {
            var moved = false
            for (i in 0 until n) for (j in i + 1 until n) {
                var dx = x[j] - x[i]
                var dy = y[j] - y[i]
                var d = sqrt(dx * dx + dy * dy)
                val need = radius[i] + radius[j] + LABEL_GAP
                if (d >= need) continue
                if (d < 0.01f) { dx = 1f; dy = 0f; d = 1f }
                val push = (need - d) / 2f
                x[i] -= dx / d * push; y[i] -= dy / d * push
                x[j] += dx / d * push; y[j] += dy / d * push
                moved = true
            }
            if (!moved) return
        }
    }

    /** Nodes far beyond the bulk of the graph are moved back to just outside it (keeping their direction). */
    internal fun pullInStragglers(x: FloatArray, y: FloatArray) {
        val n = x.size
        if (n < 4) return
        val cx = x.average().toFloat()
        val cy = y.average().toFloat()
        val dist = FloatArray(n) { sqrt((x[it] - cx) * (x[it] - cx) + (y[it] - cy) * (y[it] - cy)) }
        val ring = dist.sorted()[(n * 0.8f).toInt().coerceAtMost(n - 1)] * 1.2f
        if (ring <= 0f) return
        for (i in 0 until n) {
            if (dist[i] <= ring) continue
            val target = ring + (dist[i] - ring) * 0.15f
            x[i] = cx + (x[i] - cx) / dist[i] * target
            y[i] = cy + (y[i] - cy) / dist[i] * target
        }
    }
}
