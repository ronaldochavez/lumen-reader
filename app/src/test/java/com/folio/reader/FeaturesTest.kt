package com.folio.reader

import com.folio.reader.data.StatsRepository
import com.folio.reader.data.db.DayTotal
import com.folio.reader.ui.reader.SearchController
import com.folio.reader.ui.reader.splitSentences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class FeaturesTest {

    private val today = LocalDate.of(2026, 9, 29)
    private fun day(daysAgo: Long, minutes: Long) = DayTotal(today.minusDays(daysAgo).toString(), minutes * 60, 3, 1000)

    @Test
    fun streak_countsConsecutiveDays_andSurvivesUntilTodayIsRead() {
        // Read yesterday, the day before, and 3 days before that with a gap: current streak = 2 (today not read yet).
        val s = StatsRepository.summarize(listOf(day(1, 10), day(2, 5), day(4, 30), day(5, 30), day(6, 30)), goal = 20, today = today)
        assertEquals(2, s.streak)
        assertEquals(3, s.bestStreak)
        assertEquals(0, s.todayMinutes)
        assertEquals(7, s.last7.size)
        assertEquals(today, s.last7.last().date)

        val withToday = StatsRepository.summarize(listOf(day(0, 25), day(1, 10)), goal = 20, today = today)
        assertEquals(2, withToday.streak)
        assertEquals(1f, withToday.goalProgress)
    }

    @Test
    fun streak_ignoresDaysUnderAMinute() {
        val s = StatsRepository.summarize(listOf(DayTotal(today.toString(), 30, 0, 0), day(1, 10)), goal = 20, today = today)
        assertEquals(1, s.streak) // today had only 30 s, so the streak rests on yesterday
    }

    @Test
    fun durationFormat() {
        assertEquals("45 min", StatsRepository.formatDuration(45))
        assertEquals("2 h 10 min", StatsRepository.formatDuration(130))
        assertEquals("3 h", StatsRepository.formatDuration(180))
    }

    @Test
    fun searchFold_keepsLength_andIgnoresCaseAndAccents() {
        val text = "El NIÑO miró a Raskólnikov."
        val folded = SearchController.fold(text)
        assertEquals(text.length, folded.length)
        assertEquals("el nino miro a raskolnikov.", folded)
        val m = SearchController.patternFor("nino  miro").find(folded)!!
        assertEquals("NIÑO miró", text.substring(m.range.first, m.range.last + 1))
    }

    @Test
    fun searchPattern_escapesRegexCharacters() {
        assertTrue(SearchController.patternFor("¿quién?").containsMatchIn(SearchController.fold("—¿Quién? —dijo.")))
    }

    @Test
    fun sentences_splitOnPunctuation() {
        val s = splitSentences("Era de noche. ¿Quién llamaba? Nadie respondió.", Locale.forLanguageTag("es"))
        assertEquals(listOf("Era de noche.", "¿Quién llamaba?", "Nadie respondió."), s)
    }
}
