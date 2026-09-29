package com.folio.reader.ui.reader

import com.folio.reader.data.StatsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Counts reading time for the stats screen. Time only accrues while the reader is on screen and the reader
 * did something recently (turned a page, listened to read-aloud), so a tablet left open doesn't inflate stats.
 */
class ReadingSessionTracker(
    private val scope: CoroutineScope,
    private val stats: StatsRepository,
    private val bookId: String,
) {
    private var visible = false
    private var lastActivity = 0L
    private var seconds = 0L
    private var pages = 0
    private var chars = 0L

    init {
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (visible && System.currentTimeMillis() - lastActivity < IDLE_MS) seconds += TICK_MS / 1000
                flush()
            }
        }
    }

    fun setVisible(v: Boolean) {
        visible = v
        if (v) touch() else scope.launch { flush() }
    }

    /** Any sign the reader is actively reading. */
    fun touch() {
        lastActivity = System.currentTimeMillis()
    }

    fun pageTurned(charsAdvanced: Long) {
        pages++
        chars += charsAdvanced.coerceAtLeast(0)
        touch()
    }

    private suspend fun flush() {
        if (seconds == 0L && pages == 0 && chars == 0L) return
        val (s, p, c) = Triple(seconds, pages, chars)
        seconds = 0; pages = 0; chars = 0
        stats.record(bookId, s, p, c)
    }

    private companion object {
        const val TICK_MS = 15_000L
        /** A page usually takes well under 3 minutes; longer means the reader walked away. */
        const val IDLE_MS = 3 * 60_000L
    }
}
