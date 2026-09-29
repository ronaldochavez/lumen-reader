package com.folio.reader.ui.reader

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Routes the hardware volume buttons to the open reader. The activity asks [dispatch] first; when no reader
 * has claimed the buttons (library, sheets open, reading aloud…) they change the volume as usual.
 */
object VolumeKeys {
    private var handler: ((forward: Boolean) -> Unit)? = null

    /** True when the event was used (or swallowed) for page turning. */
    fun dispatch(event: KeyEvent): Boolean {
        val forward = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> true
            KeyEvent.KEYCODE_VOLUME_UP -> false
            else -> return false
        }
        val h = handler ?: return false
        // One page per press: holding the button doesn't race through the book. The key-up is swallowed too,
        // so the system volume panel never shows.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) h(forward)
        return true
    }

    internal fun claim(h: (Boolean) -> Unit) { handler = h }
    internal fun release(h: (Boolean) -> Unit) { if (handler === h) handler = null }
}

/** While [enabled], volume down turns to the next page and volume up to the previous one. */
@Composable
fun VolumeKeyPaging(enabled: Boolean, onTurn: (forward: Boolean) -> Unit) {
    val turn by rememberUpdatedState(onTurn)
    DisposableEffect(enabled) {
        val h: (Boolean) -> Unit = { turn(it) }
        if (enabled) VolumeKeys.claim(h)
        onDispose { VolumeKeys.release(h) }
    }
}
