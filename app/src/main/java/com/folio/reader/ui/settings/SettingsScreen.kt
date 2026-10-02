package com.folio.reader.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import com.folio.reader.data.BackupManager
import com.folio.reader.data.SettingsRepository
import com.folio.reader.ai.OllamaService
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import com.folio.reader.data.AppTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.folio.reader.ui.theme.Folio
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.reader.AppContainer
import com.folio.reader.ai.AiProvider
import com.folio.reader.ai.AiService
import com.folio.reader.ai.GeminiService
import com.folio.reader.container
import com.folio.reader.data.AiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T) = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val appTheme = c.settings.appTheme.state(AppTheme.SYSTEM)
    fun setAppTheme(t: AppTheme) = viewModelScope.launch { c.settings.setAppTheme(t) }

    val accent = c.settings.accentColor.state(null)
    /** null restores the default plum. */
    fun setAccent(argb: Int?) = viewModelScope.launch { c.settings.setAccentColor(argb) }

    val hasAnthropicKey = c.settings.hasApiKey.state(false)
    val hasGeminiKey = c.settings.geminiKey.map { !it.isNullOrBlank() }.state(false)
    val provider = c.ai.activeProvider.state(null)
    val model = c.settings.model.state(AiModel.OPUS)
    val testResult = MutableStateFlow<String?>(null)
    val testing = MutableStateFlow(false)

    fun saveAnthropicKey(key: String) = viewModelScope.launch { c.settings.setApiKey(key); testResult.value = null }
    fun clearAnthropicKey() = viewModelScope.launch { c.settings.setApiKey(null); testResult.value = null }
    fun saveGeminiKey(key: String) = viewModelScope.launch { c.settings.setGeminiKey(key); testResult.value = null }
    fun clearGeminiKey() = viewModelScope.launch { c.settings.setGeminiKey(null); testResult.value = null }
    fun setModel(m: AiModel) = viewModelScope.launch { c.settings.setModel(m) }

    fun test() = viewModelScope.launch {
        testing.value = true
        testResult.value = runCatching { "✓ " + c.ai.testConnection() }.getOrElse { AiService.friendlyError(it) }
        testing.value = false
    }

    // ---------- Local AI (Ollama) ----------

    val localUrl = c.settings.localAiUrl.state(null)
    val localModel = c.settings.localAiModel.state(SettingsRepository.DEFAULT_LOCAL_MODEL)
    val localStatus = MutableStateFlow<String?>(null)
    val localChecking = MutableStateFlow(false)

    fun saveLocalAi(url: String, model: String) = viewModelScope.launch {
        c.settings.setLocalAi(OllamaService.normalizeUrl(url), model)
        c.ai.resetLocalReachability()
        checkLocalAi()
    }

    fun clearLocalAi() = viewModelScope.launch {
        c.settings.setLocalAi(null, null)
        c.ai.resetLocalReachability()
        localStatus.value = null
    }

    fun checkLocalAi() = viewModelScope.launch {
        localChecking.value = true
        localStatus.value = runCatching { "✓ " + c.ai.local.testConnection() }.getOrElse { AiService.friendlyError(it) }
        localChecking.value = false
    }

    val volumeKeys = c.settings.volumeKeysTurnPages.state(true)
    fun setVolumeKeys(on: Boolean) = viewModelScope.launch { c.settings.setVolumeKeysTurnPages(on) }

    // ---------- Backup ----------

    val dataBusy = MutableStateFlow(false)
    val dataStatus = MutableStateFlow<String?>(null)
    /** A backup the reader picked, waiting for them to confirm the restore. */
    val pendingRestore = MutableStateFlow<Pair<Uri, BackupManager.Manifest>?>(null)

    fun exportBackup(uri: Uri) = dataWork {
        val m = c.backup.export(uri)
        "✓ Backup saved: " + BackupManager.run {
            "${count(m.books, "book", "books")}, ${count(m.highlights, "highlight", "highlights")}, ${count(m.bookmarks, "bookmark", "bookmarks")}."
        }
    }

    fun exportHighlights(uri: Uri) = dataWork {
        val n = c.backup.exportHighlights(uri)
        if (n == 0) "You don't have any highlights yet, so the file is almost empty." else "✓ Exported ${BackupManager.count(n, "highlight", "highlights")}."
    }

    fun inspectRestore(uri: Uri) = dataWork {
        pendingRestore.value = uri to c.backup.inspect(uri)
        null
    }

    fun cancelRestore() { pendingRestore.value = null }

    fun confirmRestore(restart: () -> Unit) {
        val (uri, _) = pendingRestore.value ?: return
        pendingRestore.value = null
        dataWork {
            c.backup.restore(uri)
            restart()
            null
        }
    }

    private fun dataWork(block: suspend () -> String?) = viewModelScope.launch {
        dataBusy.value = true
        dataStatus.value = null
        dataStatus.value = runCatching { block() }.getOrElse { it.message ?: "Something went wrong." }
        dataBusy.value = false
    }

    companion object {
        val Factory = viewModelFactory { initializer { SettingsViewModel(container) } }
    }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val hasAnthropic by vm.hasAnthropicKey.collectAsStateWithLifecycle()
    val hasGemini by vm.hasGeminiKey.collectAsStateWithLifecycle()
    val provider by vm.provider.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val testResult by vm.testResult.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val appTheme by vm.appTheme.collectAsStateWithLifecycle()
    val accent by vm.accent.collectAsStateWithLifecycle()
    val localUrl by vm.localUrl.collectAsStateWithLifecycle()
    val localModel by vm.localModel.collectAsStateWithLifecycle()
    val localStatus by vm.localStatus.collectAsStateWithLifecycle()
    val localChecking by vm.localChecking.collectAsStateWithLifecycle()
    val volumeKeys by vm.volumeKeys.collectAsStateWithLifecycle()
    val dataBusy by vm.dataBusy.collectAsStateWithLifecycle()
    val dataStatus by vm.dataStatus.collectAsStateWithLifecycle()
    val pendingRestore by vm.pendingRestore.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(vm::exportBackup)
    }
    val exportHighlights = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri?.let(vm::exportHighlights)
    }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::inspectRestore)
    }

    pendingRestore?.let { (_, m) ->
        val date = remember(m.createdAt) {
            java.text.SimpleDateFormat("MMM d, yyyy, h:mm a", java.util.Locale.US).format(java.util.Date(m.createdAt))
        }
        AlertDialog(
            onDismissRequest = vm::cancelRestore,
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "Backup from $date: " + BackupManager.run {
                        "${count(m.books, "book", "books")}, ${count(m.highlights, "highlight", "highlights")} and ${count(m.bookmarks, "bookmark", "bookmarks")}.\n\n"
                    } +
                        "It will replace your current library. What you have now is kept aside, just in case. " +
                        "Lumen will restart when it's done."
                )
            },
            confirmButton = { Button(onClick = { vm.confirmRestore { restartApp(context) } }, shape = CircleShape) { Text("Restore") } },
            dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Cancel") } },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text("Make it yours", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
            Text("Settings", style = MaterialTheme.typography.displaySmall)
        }

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.DarkMode, null, tint = MaterialTheme.colorScheme.primary)
                Text("  Appearance", style = MaterialTheme.typography.titleLarge)
            }
            FlowRow(
                Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppTheme.entries.forEach { t ->
                    val selected = t == appTheme
                    Surface(
                        onClick = { vm.setAppTheme(t) },
                        shape = CircleShape,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when (t) {
                                    AppTheme.SYSTEM -> Icons.Rounded.BrightnessAuto
                                    AppTheme.LIGHT -> Icons.Rounded.LightMode
                                    AppTheme.DARK -> Icons.Rounded.DarkMode
                                    AppTheme.BLACK -> Icons.Rounded.Contrast
                                },
                                null,
                                Modifier.size(18.dp),
                            )
                            Text("  ${t.label}", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            Text(
                "\"Dark\" uses plum tones; \"Black\" is pure black with neutral grays (ideal for OLED screens). " +
                    "Both also switch the reader's theme, which you can change separately from \"Aa\" while reading.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )

            Text("Accent color", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 18.dp))
            Text(
                "The color of the main buttons, selections and indicators.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AccentPicker(accent, onPick = vm::setAccent)
        }

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                Text("  Reading", style = MaterialTheme.typography.titleLarge)
            }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Turn pages with the volume buttons", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Volume down: next page. Volume up: previous page. While Lumen reads aloud, the buttons " +
                            "control the volume again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = volumeKeys, onCheckedChange = vm::setVolumeKeys)
            }
        }

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Text("  AI reading companion", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "Select any text while you read and ask \"who is this?\". Lumen only sends the book from the " +
                    "beginning up to your current page, and the AI is instructed never to reveal what comes next.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Answering now: ", style = MaterialTheme.typography.labelLarge)
                Text(
                    when (provider) {
                        AiProvider.LOCAL -> "$localModel · your computer"
                        AiProvider.CLAUDE -> "${model.label} · your key"
                        AiProvider.GEMINI -> "Gemini · free"
                        null -> "nobody yet — turn on an option below"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (provider == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            if (provider != null) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = vm::test, enabled = !testing, shape = CircleShape) {
                        if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Test connection")
                    }
                }
            }
            testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp)) }
        }

        // ---- Local AI ----
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Free on your computer (Ollama)", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (localUrl != null) Icon(Icons.Rounded.CheckCircle, "Active", tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                "Runs a model like Qwen on your own computer with Ollama: free, no limits, and your books never leave " +
                    "home. Lumen uses it whenever your computer is on and on the same Wi-Fi, and falls back to the " +
                    "options below when it isn't. Long books are trimmed to the most recent chapters plus earlier " +
                    "passages about what you ask.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (localUrl != null) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Computer, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("  $localModel · $localUrl", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::checkLocalAi, enabled = !localChecking, shape = CircleShape) {
                        if (localChecking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Check")
                    }
                    OutlinedButton(onClick = vm::clearLocalAi, shape = CircleShape) { Text("Remove") }
                }
            } else {
                LocalAiInput(defaultModel = localModel, onSave = vm::saveLocalAi)
                Text(
                    "On your computer: install Ollama from ollama.com, run \"ollama pull ${SettingsRepository.DEFAULT_LOCAL_MODEL}\", " +
                        "and let it accept connections from your network (see the README).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            localStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp)) }
        }

        // ---- Free tier ----
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("1 · Free with Gemini", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (hasGemini) Icon(Icons.Rounded.CheckCircle, "Active", tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                "Uses Google Gemini with a free key from Google AI Studio (no card needed). It has per-minute and daily " +
                    "limits; if you run out, you can keep going with your own Anthropic key.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (hasGemini) {
                SavedKeyRow("Gemini key saved and encrypted", onRemove = vm::clearGeminiKey)
            } else {
                OutlinedButton(
                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GeminiService.API_KEY_URL))) } },
                    shape = CircleShape,
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("Get a free key") }
                KeyInput(label = "Gemini key", placeholder = "AIza…", onSave = vm::saveGeminiKey)
            }
            Text(
                "Privacy: on the free plan, Google may use the text you send to improve its products.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        // ---- Personal key ----
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("2 · Your own key (Claude)", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (hasAnthropic) Icon(Icons.Rounded.CheckCircle, "Active", tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                "Optional. More accurate answers, no daily limits, and your text isn't used for training. " +
                    "You pay per use on your Anthropic account. If you add it, it takes priority over Gemini.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (hasAnthropic) {
                SavedKeyRow("Anthropic key saved and encrypted", onRemove = vm::clearAnthropicKey)
                Text("Model", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                AiModel.entries.forEach { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.setModel(m) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = model == m, onClick = { vm.setModel(m) })
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(m.label, style = MaterialTheme.typography.titleSmall)
                            Text(m.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                KeyInput(label = "Anthropic API key", placeholder = "sk-ant-…", onSave = vm::saveAnthropicKey)
                Text(
                    "Get one at console.anthropic.com → API Keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Backup, null, tint = MaterialTheme.colorScheme.primary)
                Text("  Your data", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "A backup saves your books, highlights, notes, bookmarks, collections, progress and stats in a single " +
                    "file. Keep it in Drive or on your computer so you don't lose anything if you switch devices.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(
                Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { createBackup.launch(BackupManager.backupFileName()) }, enabled = !dataBusy, shape = CircleShape) {
                    Text("Create backup")
                }
                OutlinedButton(onClick = { pickBackup.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !dataBusy, shape = CircleShape) {
                    Text("Restore")
                }
                OutlinedButton(onClick = { exportHighlights.launch(BackupManager.highlightsFileName()) }, enabled = !dataBusy, shape = CircleShape) {
                    Text("Export highlights")
                }
            }
            if (dataBusy) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  Working…", style = MaterialTheme.typography.bodySmall)
                }
            }
            dataStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
            Text(
                "\"Export highlights\" creates a Markdown file with your quotes and notes, grouped by book and chapter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Card {
            Text("Privacy", style = MaterialTheme.typography.titleLarge)
            Text(
                "Your books, highlights and collections live only on this device. Keys are stored encrypted with the " +
                    "Android Keystore. When you use the AI, the book's text up to your position and your question are " +
                    "sent to the active provider: your own computer (Ollama), Google or Anthropic.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Text(
            "Lumen ${versionName(context)} · made for readers who forget names",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp),
        )
    }
}

/** Preset accent swatches plus a custom hue slider. Saves on release so dragging doesn't hammer storage. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentPicker(current: Int?, onPick: (Int?) -> Unit) {
    val currentColor = current?.let { Color(it) } ?: Folio.Plum
    val presetName = Folio.accentPresets.entries.firstOrNull { it.value.toArgb() == currentColor.toArgb() }?.key
    var showCustom by remember { mutableStateOf(presetName == null) }
    var hue by remember { mutableFloatStateOf(hueOf(currentColor)) }
    val custom = Color.hsv(hue, 0.62f, 0.66f)

    FlowRow(
        Modifier.padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Folio.accentPresets.forEach { (name, color) ->
            Swatch(
                fill = SolidColor(color),
                selected = presetName == name,
                description = name,
                onClick = {
                    showCustom = false
                    onPick(if (color == Folio.Plum) null else color.toArgb())
                },
            )
        }
        Swatch(
            fill = Brush.sweepGradient((0..6).map { Color.hsv(it * 60f % 360f, 0.62f, 0.8f) }),
            selected = presetName == null,
            description = "Custom",
            onClick = { showCustom = true; hue = hueOf(currentColor) },
        )
    }

    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            presetName ?: "Custom",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (current != null) TextButton(onClick = { showCustom = false; onPick(null) }) { Text("Reset") }
    }

    if (showCustom) {
        Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(14.dp)
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient((0..12).map { Color.hsv(it * 30f % 360f, 0.62f, 0.66f) }))
            )
            Slider(
                value = hue,
                onValueChange = { hue = it },
                onValueChangeFinished = { onPick(custom.toArgb()) },
                valueRange = 0f..359f,
                colors = SliderDefaults.colors(
                    thumbColor = custom,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                ),
            )
        }
        Text(
            "Slide to pick a hue; it adjusts itself so button text always stays readable.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Swatch(fill: Brush, selected: Boolean, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(fill)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = Color.White)
    }
}

private fun hueOf(c: Color): Float {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    return hsv[0]
}

@Composable
private fun SavedKeyRow(text: String, onRemove: () -> Unit) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text("  $text", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onRemove, shape = CircleShape) { Text("Remove") }
    }
}

@Composable
private fun LocalAiInput(defaultModel: String, onSave: (url: String, model: String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(defaultModel) }
    Column(Modifier.padding(top = 12.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Your computer's address") },
            placeholder = { Text("192.168.1.65") },
            leadingIcon = { Icon(Icons.Rounded.Computer, null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = { onSave(url.trim(), model.trim()) }, enabled = url.isNotBlank() && model.isNotBlank(), shape = CircleShape) {
            Text("Save and check")
        }
    }
}

@Composable
private fun KeyInput(label: String, placeholder: String, onSave: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 12.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(Icons.Rounded.Key, null) },
            trailingIcon = {
                IconButton(onClick = { show = !show }) {
                    Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show")
                }
            },
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = { onSave(input.trim()); input = "" }, enabled = input.isNotBlank(), shape = CircleShape) { Text("Save") }
    }
}

private fun versionName(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

/** After a restore the database is closed: start a fresh task and end this process so everything reopens. */
private fun restartApp(context: android.content.Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    context.startActivity(Intent.makeRestartActivityTask(launch.component))
    Runtime.getRuntime().exit(0)
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) { content() }
    }
}
