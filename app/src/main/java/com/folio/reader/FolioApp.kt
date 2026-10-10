package com.folio.reader

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.webkit.WebView
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import com.folio.reader.ai.AiService
import com.folio.reader.ai.ClaudeService
import com.folio.reader.ai.GeminiService
import com.folio.reader.ai.OllamaService
import com.folio.reader.ai.ReadingSnapshotCache
import com.folio.reader.ai.SpoilerFreeContextBuilder
import com.folio.reader.data.BackupManager
import com.folio.reader.data.BookRepository
import com.folio.reader.data.SettingsRepository
import com.folio.reader.data.StatsRepository
import com.folio.reader.data.db.AppDatabase
import com.folio.reader.data.dictionary.DictionaryRepository

class FolioApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Lets chrome://inspect attach to the reader WebView in debug builds.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
        container = AppContainer(this)
        Thread { container.backup.cleanupOldRestoreCopy() }.start()
    }
}

/** Manual dependency container; small enough that a DI framework isn't worth it. */
class AppContainer(context: Context) {
    val db = AppDatabase.build(context)
    val settings = SettingsRepository(context)
    val appContext: Context = context.applicationContext
    val books = BookRepository(context, db)
    val stats = StatsRepository(db, settings)
    val ai = AiService(settings, ClaudeService(settings), GeminiService(settings), OllamaService(settings))
    val contextBuilder = SpoilerFreeContextBuilder(books)
    val snapshots = ReadingSnapshotCache()
    val backup = BackupManager(context, db, books)
    val dictionary = DictionaryRepository(context)
}

val CreationExtras.container: AppContainer
    get() = (this[APPLICATION_KEY] as FolioApp).container
