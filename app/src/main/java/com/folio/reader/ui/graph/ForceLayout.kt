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
        }

        val n = nodes.size
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
        var temperature = size / 8f
        val dx = FloatArray(n)
        val dy = FloatArray(n)

        repeat(iterations) {
            dx.fill(0f); dy.fill(0f)
            for (i in 0 until n) for (j in i + 1 until n) {
                var ddx = x[i] - x[j]
                var ddy = y[i] - y[j]
                var dist = sqrt(ddx * ddx + ddy * ddy)
                if (dist < 0.01f) { ddx = rnd.nextFloat(); ddy = rnd.nextFloat(); dist = 0.5f }
                val force = k * k / dist
                val fx = ddx / dist * force
                val fy = ddy / dist * force
                dx[i] += fx; dy[i] += fy
                dx[j] -= fx; dy[j] -= fy
            }
            for ((a, b) in edges) {
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
                val pull = 0.02f + 0.03f * (importance[nodes[i]] ?: 3)
                dx[i] += (center - x[i]) * pull
                dy[i] += (center - y[i]) * pull
                val len = max(sqrt(dx[i] * dx[i] + dy[i] * dy[i]), 0.01f)
                x[i] += dx[i] / len * min(len, temperature)
                y[i] += dy[i] / len * min(len, temperature)
            }
            temperature = max(temperature * 0.985f, 1.5f)
        }
        return nodes.withIndex().associate { (i, id) -> id to Offset(x[i], y[i]) }
    }
}
