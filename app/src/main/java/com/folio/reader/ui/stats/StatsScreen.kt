package com.folio.reader.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.reader.AppContainer
import com.folio.reader.container
import com.folio.reader.data.DayStat
import com.folio.reader.data.StatsRepository
import com.folio.reader.data.StatsSummary
import com.folio.reader.data.db.BookEntity
import com.folio.reader.ui.components.BookCover
import com.folio.reader.ui.components.ProgressBar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

data class ReadingNow(val book: BookEntity, val minutesLeft: Long?)

class StatsViewModel(private val c: AppContainer) : ViewModel() {
    val summary = c.stats.summary.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsSummary())

    val readingNow = c.books.observeBooks().map { books ->
        books.filter { it.lastOpenedAt != null && it.progress in 0.005f..0.995f }.take(5).map { b ->
            val count = c.books.chapters(b.id).size
            ReadingNow(b, c.stats.minutesLeft(b, count, b.spineIndex))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setGoal(minutes: Int) = viewModelScope.launch { c.settings.setDailyGoal(minutes) }

    companion object {
        val Factory = viewModelFactory { initializer { StatsViewModel(container) } }
    }
}

@Composable
fun StatsScreen(onOpenBook: (String) -> Unit, vm: StatsViewModel = viewModel(factory = StatsViewModel.Factory)) {
    val s by vm.summary.collectAsStateWithLifecycle()
    val reading by vm.readingNow.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text("Every page counts", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
            Text("Your progress", style = MaterialTheme.typography.displaySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Streak hero tile
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(18.dp)) {
                    Icon(Icons.Rounded.LocalFireDepartment, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(28.dp))
                    Text(
                        "${s.streak}",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        if (s.streak == 1) "day streak" else "days streak",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        "Best: ${s.bestStreak}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .7f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            // Daily goal ring
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    GoalRing(s.goalProgress, Modifier.size(84.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${s.todayMinutes}", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("of ${s.goalMinutes} min", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Text(
                        if (s.goalProgress >= 1f) "Today's goal reached!" else "Today's goal",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        Card {
            Text("Daily goal", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 20, 30, 45, 60).forEach { m ->
                    val sel = m == s.goalMinutes
                    Surface(
                        onClick = { vm.setGoal(m) },
                        shape = CircleShape,
                        color = if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = if (sel) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                    ) { Text("$m min", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
                }
            }
        }

        Card {
            Text("Minutes per day", style = MaterialTheme.typography.titleMedium)
            Text("Last 7 days · tap a bar for details", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            WeekBars(s.last7, s.goalMinutes, Modifier.fillMaxWidth().height(180.dp).padding(top = 12.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Total read", StatsRepository.formatDuration(s.totalMinutes), Modifier.weight(1f))
            StatTile("This month", StatsRepository.formatDuration(s.monthMinutes), Modifier.weight(1f))
            StatTile("Pages", "${s.totalPages}", Modifier.weight(1f))
        }

        if (reading.isNotEmpty()) {
            Card {
                Text("Reading now", style = MaterialTheme.typography.titleMedium)
                reading.forEach { r ->
                    Surface(onClick = { onOpenBook(r.book.id) }, color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            BookCover(r.book, Modifier.width(44.dp), corner = 6.dp, elevation = 2.dp)
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(r.book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                ProgressBar(r.book.progress, Modifier.padding(vertical = 6.dp), height = 4.dp)
                                Text(
                                    "${(r.book.progress * 100).toInt()}%" +
                                        (r.minutesLeft?.let { " · ~${StatsRepository.formatDuration(it)} left" } ?: ""),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Text(
                    "Time left is estimated from your real reading pace; it gets more accurate the more you read.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun GoalRing(progress: Float, modifier: Modifier, content: @Composable () -> Unit) {
    val track = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .12f)
    val fill = MaterialTheme.colorScheme.primary
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (progress > 0f) drawArc(fill, -90f, 360f * progress, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        content()
    }
}

/**
 * Single-series bar chart: one hue, thin bars rounded at the data end and anchored to a recessive baseline,
 * a dashed goal reference line, direct label only on the selected bar (today by default).
 */
@Composable
private fun WeekBars(days: List<DayStat>, goal: Int, modifier: Modifier) {
    if (days.isEmpty()) return
    val measurer = rememberTextMeasurer()
    var selected by remember(days) { mutableStateOf(days.lastIndex) }
    val barColor = MaterialTheme.colorScheme.primary
    val barMuted = MaterialTheme.colorScheme.primary.copy(alpha = .45f)
    val axis = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val labelStyle = TextStyle(fontSize = 11.sp, color = muted)
    val today = LocalDate.now()

    Canvas(
        modifier.pointerInput(days) {
            detectTapGestures { p ->
                val slot = size.width / days.size
                selected = (p.x / slot).toInt().coerceIn(0, days.lastIndex)
            }
        }
    ) {
        val labelH = 18.dp.toPx()
        val topPad = 22.dp.toPx()
        val plotH = size.height - labelH - topPad
        val maxV = maxOf(goal, days.maxOf { it.minutes }, 1).toFloat()
        val slot = size.width / days.size
        val barW = minOf(slot * 0.46f, 28.dp.toPx())
        val baseY = topPad + plotH

        // Goal reference line
        val goalY = baseY - plotH * (goal / maxV)
        drawLine(muted.copy(alpha = .6f), Offset(0f, goalY), Offset(size.width, goalY), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        val goalText = measurer.measure("Goal $goal", labelStyle)
        drawText(goalText, topLeft = Offset(size.width - goalText.size.width, goalY - goalText.size.height - 2.dp.toPx()))

        // Baseline
        drawLine(axis, Offset(0f, baseY), Offset(size.width, baseY), 1.dp.toPx())

        days.forEachIndexed { i, d ->
            val cx = slot * i + slot / 2
            val h = if (d.minutes <= 0) 0f else maxOf(plotH * (d.minutes / maxV), 4.dp.toPx())
            if (h > 0f) {
                val r = 4.dp.toPx()
                // Rounded data end, square foot on the baseline.
                drawRoundRect(if (i == selected) barColor else barMuted, Offset(cx - barW / 2, baseY - h), Size(barW, h), CornerRadius(r, r))
                drawRect(if (i == selected) barColor else barMuted, Offset(cx - barW / 2, baseY - minOf(h, r)), Size(barW, minOf(h, r)))
            }
            val day = if (d.date == today) "Today" else d.date.dayOfWeek.getDisplayName(DateTextStyle.SHORT, Locale.ENGLISH)
            val dayText = measurer.measure(day, labelStyle.copy(fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal, color = if (i == selected) ink else muted))
            drawText(dayText, topLeft = Offset(cx - dayText.size.width / 2, baseY + 4.dp.toPx()))
            if (i == selected) {
                val v = measurer.measure("${d.minutes} min", TextStyle(fontSize = 12.sp, color = ink, fontWeight = FontWeight.SemiBold))
                drawText(v, topLeft = Offset((cx - v.size.width / 2).coerceIn(0f, size.width - v.size.width), baseY - h - v.size.height - 4.dp.toPx()))
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}
