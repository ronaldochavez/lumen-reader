package com.folio.reader.data

import com.folio.reader.data.db.AppDatabase
import com.folio.reader.data.db.BookEntity
import com.folio.reader.data.db.BookFormat
import com.folio.reader.data.db.DayTotal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import kotlin.math.roundToLong

data class DayStat(val date: LocalDate, val minutes: Int)

data class StatsSummary(
    val todayMinutes: Int = 0,
    val goalMinutes: Int = 20,
    /** Consecutive days (ending today, or yesterday if today has no reading yet) with ≥ [STREAK_MIN_SECONDS]. */
    val streak: Int = 0,
    val bestStreak: Int = 0,
    val last7: List<DayStat> = emptyList(),
    val totalMinutes: Long = 0,
    val monthMinutes: Long = 0,
    val totalPages: Long = 0,
) {
    val goalProgress: Float get() = if (goalMinutes <= 0) 0f else (todayMinutes.toFloat() / goalMinutes).coerceIn(0f, 1f)
}

class StatsRepository(private val db: AppDatabase, private val settings: SettingsRepository) {

    suspend fun record(bookId: String, seconds: Long, pages: Int, chars: Long) {
        if (seconds <= 0 && pages <= 0 && chars <= 0) return
        db.stats().add(LocalDate.now().toString(), bookId, seconds, pages, chars)
    }

    val summary: Flow<StatsSummary> = combine(db.stats().observeDays(), settings.dailyGoalMinutes) { days, goal ->
        summarize(days, goal, LocalDate.now())
    }

    /**
     * Estimated minutes left to finish [book], from this book's measured speed (or the reader's overall speed,
     * or a typical 1,000 chars ≈ 200 words per minute). Null when there's nothing to estimate.
     */
    suspend fun minutesLeft(book: BookEntity, pageCount: Int, currentPage: Int): Long? {
        val format = BookFormat.of(book.format)
        val own = db.stats().bookTotals(book.id)
        val all = db.stats().allTotals()
        return if (format == BookFormat.COMIC) {
            val secPerPage = speed(own.seconds, own.pages?.toLong(), all.seconds, all.pages?.toLong(), default = 1.0 / 20)
                ?.let { 1 / it } ?: return null
            ((pageCount - currentPage - 1).coerceAtLeast(0) * secPerPage / 60).roundToLong()
        } else {
            if (book.totalChars <= 0) return null
            val charsPerSec = speed(own.seconds, own.chars, all.seconds, all.chars, default = 1000.0 / 60) ?: return null
            val left = book.totalChars * (1f - book.progress).coerceIn(0f, 1f)
            (left / charsPerSec / 60).roundToLong()
        }
    }

    /** Units per second: this book if we have ≥ 5 min of data, else overall if ≥ 10 min, else [default]. */
    private fun speed(ownSec: Long?, ownUnits: Long?, allSec: Long?, allUnits: Long?, default: Double): Double? {
        fun rate(s: Long?, u: Long?, minSec: Long) = if ((s ?: 0) >= minSec && (u ?: 0) > 0) u!!.toDouble() / s!! else null
        return rate(ownSec, ownUnits, 300) ?: rate(allSec, allUnits, 600) ?: default
    }

    companion object {
        const val STREAK_MIN_SECONDS = 60L

        fun summarize(days: List<DayTotal>, goal: Int, today: LocalDate): StatsSummary {
            val byDate = days.associate { LocalDate.parse(it.date) to it }
            fun readOn(d: LocalDate) = (byDate[d]?.seconds ?: 0) >= STREAK_MIN_SECONDS

            var streak = 0
            var d = if (readOn(today)) today else today.minusDays(1)
            while (readOn(d)) { streak++; d = d.minusDays(1) }

            var best = 0
            var run = 0
            var prev: LocalDate? = null
            for (date in byDate.keys.filter(::readOn).sorted()) {
                run = if (prev != null && prev.plusDays(1) == date) run + 1 else 1
                best = maxOf(best, run)
                prev = date
            }

            val last7 = (6 downTo 0).map { back ->
                val date = today.minusDays(back.toLong())
                DayStat(date, ((byDate[date]?.seconds ?: 0) / 60).toInt())
            }
            return StatsSummary(
                todayMinutes = ((byDate[today]?.seconds ?: 0) / 60).toInt(),
                goalMinutes = goal,
                streak = streak,
                bestStreak = maxOf(best, streak),
                last7 = last7,
                totalMinutes = days.sumOf { it.seconds } / 60,
                monthMinutes = days.filter { LocalDate.parse(it.date).let { x -> x.year == today.year && x.month == today.month } }
                    .sumOf { it.seconds } / 60,
                totalPages = days.sumOf { it.pages.toLong() },
            )
        }

        fun formatDuration(minutes: Long): String = when {
            minutes < 1 -> "under 1 min"
            minutes < 60 -> "$minutes min"
            else -> "${minutes / 60} h" + if (minutes % 60 != 0L) " ${minutes % 60} min" else ""
        }
    }
}
