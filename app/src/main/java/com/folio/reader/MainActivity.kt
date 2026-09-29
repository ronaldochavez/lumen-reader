package com.folio.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.folio.reader.data.AppTheme
import com.folio.reader.ui.FolioNavHost
import com.folio.reader.ui.reader.VolumeKeys
import com.folio.reader.ui.theme.Folio
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.ThemeStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = (application as FolioApp).container.settings
        // Read once up front so the first frame already uses the chosen theme (no light→dark flash).
        val initialTheme = runBlocking { settings.appTheme.first() }
        val initialAccent = runBlocking { settings.accentColor.first() }
        setContent {
            val appTheme by settings.appTheme.collectAsStateWithLifecycle(initialTheme)
            val accentArgb by settings.accentColor.collectAsStateWithLifecycle(initialAccent)
            val style = when (appTheme) {
                AppTheme.SYSTEM -> if (isSystemInDarkTheme()) ThemeStyle.DARK else ThemeStyle.LIGHT
                AppTheme.LIGHT -> ThemeStyle.LIGHT
                AppTheme.DARK -> ThemeStyle.DARK
                AppTheme.BLACK -> ThemeStyle.BLACK
            }
            val dark = style != ThemeStyle.LIGHT
            // Status/navigation bar icons follow the app theme, not just the system setting.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose { }
            }
            FolioTheme(style = style, accent = accentArgb?.let { Color(it) } ?: Folio.Plum) {
                FolioNavHost()
            }
        }
        if (savedInstanceState == null) handleOpenIntent(intent)
    }

    // Before the focused view (the reader's WebView) sees it, so the volume buttons can turn pages.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        VolumeKeys.dispatch(event) || super.dispatchKeyEvent(event)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenIntent(intent)
    }

    /** "Open with Lumen" from a file manager or browser download. */
    private fun handleOpenIntent(intent: Intent?) {
        val uri: Uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        val container = (application as FolioApp).container
        lifecycleScope.launch {
            val msg = runCatching { container.books.import(uri) }
                .fold({ "Libro agregado a tu biblioteca" }, { "No se pudo importar: ${it.message}" })
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        val LIGHT_SCRIM = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
