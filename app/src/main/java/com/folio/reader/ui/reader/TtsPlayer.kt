package com.folio.reader.ui.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Sentence split for plain text (PDF pages), locale-aware; long sentences are cut at line breaks. */
fun splitSentences(text: String, locale: Locale): List<String> {
    val it = java.text.BreakIterator.getSentenceInstance(locale)
    it.setText(text)
    val out = mutableListOf<String>()
    var start = it.first()
    var end = it.next()
    while (end != java.text.BreakIterator.DONE) {
        val s = text.substring(start, end)
        val pieces = if (s.length > 600) s.split('\n') else listOf(s)
        pieces.map { p -> p.replace(Regex("\\s+"), " ").trim() }.filter { p -> p.isNotEmpty() }.forEach(out::add)
        start = end
        end = it.next()
    }
    return out
}

/** Speed options for read-aloud. */
val TTS_RATES = listOf(0.8f, 1f, 1.25f, 1.5f, 2f)

data class ReadAloudState(
    /** The mini-player is showing (playing or paused). */
    val active: Boolean = false,
    val playing: Boolean = false,
    val rate: Float = 1f,
    val error: String? = null,
)

/**
 * Thin wrapper over the system [TextToSpeech] engine (offline, free). Callbacks are delivered on the main thread.
 * Each utterance id is the index of the sentence in the caller's current queue.
 */
class TtsPlayer(context: Context, private val listener: Listener) {

    interface Listener {
        fun onTtsReady(ok: Boolean)
        fun onSentenceStart(index: Int)
        fun onSentenceDone(index: Int)
        fun onTtsError()
    }

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        main.post { listener.onTtsReady(ready) }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) { id.toIntOrNull()?.let { main.post { listener.onSentenceStart(it) } } }
            override fun onDone(id: String) { id.toIntOrNull()?.let { main.post { listener.onSentenceDone(it) } } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { main.post { listener.onTtsError() } }
            override fun onError(id: String?, errorCode: Int) { main.post { listener.onTtsError() } }
            // Called when we flush the queue on pause/stop: not an error.
            override fun onStop(id: String?, interrupted: Boolean) = Unit
        })
    }

    val isReady get() = ready

    /** Picks a voice for the book's language when the engine has one; otherwise keeps the default. */
    fun setLanguage(tag: String?) {
        if (tag.isNullOrBlank()) return
        val locale = Locale.forLanguageTag(tag.replace('_', '-'))
        if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) tts.language = locale
    }

    fun setRate(rate: Float) {
        tts.setSpeechRate(rate)
    }

    /** Replaces the queue with [sentences]; ids are their positions offset by [firstIndex]. */
    fun speak(sentences: List<String>, firstIndex: Int, flush: Boolean) {
        sentences.forEachIndexed { i, text ->
            val mode = if (flush && i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(text, mode, null, (firstIndex + i).toString())
        }
    }

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
