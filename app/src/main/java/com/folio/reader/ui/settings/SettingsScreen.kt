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

    val volumeKeys = c.settings.volumeKeysTurnPages.state(true)
    fun setVolumeKeys(on: Boolean) = viewModelScope.launch { c.settings.setVolumeKeysTurnPages(on) }

    // ---------- Backup ----------

    val dataBusy = MutableStateFlow(false)
    val dataStatus = MutableStateFlow<String?>(null)
    /** A backup the reader picked, waiting for them to confirm the restore. */
    val pendingRestore = MutableStateFlow<Pair<Uri, BackupManager.Manifest>?>(null)

    fun exportBackup(uri: Uri) = dataWork {
        val m = c.backup.export(uri)
        "✓ Respaldo guardado: " + BackupManager.run {
            "${count(m.books, "libro", "libros")}, ${count(m.highlights, "subrayado", "subrayados")}, ${count(m.bookmarks, "marcador", "marcadores")}."
        }
    }

    fun exportHighlights(uri: Uri) = dataWork {
        val n = c.backup.exportHighlights(uri)
        if (n == 0) "Aún no tienes subrayados; el archivo quedó casi vacío." else "✓ Exportaste ${BackupManager.count(n, "subrayado", "subrayados")}."
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
        dataStatus.value = runCatching { block() }.getOrElse { it.message ?: "Algo salió mal." }
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
            java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.forLanguageTag("es")).format(java.util.Date(m.createdAt))
        }
        AlertDialog(
            onDismissRequest = vm::cancelRestore,
            title = { Text("¿Restaurar este respaldo?") },
            text = {
                Text(
                    "Respaldo del $date: " + BackupManager.run {
                        "${count(m.books, "libro", "libros")}, ${count(m.highlights, "subrayado", "subrayados")} y ${count(m.bookmarks, "marcador", "marcadores")}.\n\n"
                    } +
                        "Reemplazará tu biblioteca actual. Lo que tienes ahora se guarda aparte por si acaso. " +
                        "Lumen se reiniciará al terminar."
                )
            },
            confirmButton = { Button(onClick = { vm.confirmRestore { restartApp(context) } }, shape = CircleShape) { Text("Restaurar") } },
            dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Cancelar") } },
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
            Text("Hazlo tuyo", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
            Text("Ajustes", style = MaterialTheme.typography.displaySmall)
        }

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.DarkMode, null, tint = MaterialTheme.colorScheme.primary)
                Text("  Apariencia", style = MaterialTheme.typography.titleLarge)
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
                "«Oscuro» usa tonos ciruela; «Negro» es negro puro con grises neutros (ideal en pantallas OLED). " +
                    "Ambos cambian también el tema del lector, que puedes ajustar aparte desde «Aa» mientras lees.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )

            Text("Color de acento", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 18.dp))
            Text(
                "El color de los botones principales, selecciones e indicadores.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AccentPicker(accent, onPick = vm::setAccent)
        }

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                Text("  Lectura", style = MaterialTheme.typography.titleLarge)
            }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pasar página con los botones de volumen", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Bajar volumen: siguiente página. Subir: anterior. Mientras Lumen lee en voz alta, los botones " +
                            "vuelven a controlar el volumen.",
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
                Text("  Compañero de lectura IA", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "Selecciona cualquier texto mientras lees y pregunta «¿quién es?». Lumen solo envía el libro desde el " +
                    "inicio hasta tu página actual, y la IA tiene instrucciones de no adelantar nada.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ahora responde: ", style = MaterialTheme.typography.labelLarge)
                Text(
                    when (provider) {
                        AiProvider.CLAUDE -> "${model.label} · tu key"
                        AiProvider.GEMINI -> "Gemini · gratis"
                        null -> "nadie aún — activa una opción abajo"
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
                        if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Probar conexión")
                    }
                }
            }
            testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp)) }
        }

        // ---- Free tier ----
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("1 · Gratis con Gemini", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (hasGemini) Icon(Icons.Rounded.CheckCircle, "Activa", tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                "Usa Google Gemini con una key gratuita de Google AI Studio (sin tarjeta). Tiene límites de uso por minuto " +
                    "y por día; si se acaban, podrás seguir con tu propia key de Anthropic.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (hasGemini) {
                SavedKeyRow("Key de Gemini guardada y cifrada", onRemove = vm::clearGeminiKey)
            } else {
                OutlinedButton(
                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GeminiService.API_KEY_URL))) } },
                    shape = CircleShape,
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("Obtener key gratis") }
                KeyInput(label = "Key de Gemini", placeholder = "AIza…", onSave = vm::saveGeminiKey)
            }
            Text(
                "Privacidad: en el plan gratuito, Google puede usar el texto que se envía para mejorar sus productos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        // ---- Personal key ----
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("2 · Tu key personal (Claude)", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (hasAnthropic) Icon(Icons.Rounded.CheckCircle, "Activa", tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                "Opcional. Respuestas más precisas, sin límites diarios y sin que tus textos se usen para entrenar. " +
                    "Pagas por uso en tu cuenta de Anthropic. Si la agregas, tiene prioridad sobre Gemini.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (hasAnthropic) {
                SavedKeyRow("Key de Anthropic guardada y cifrada", onRemove = vm::clearAnthropicKey)
                Text("Modelo", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
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
                KeyInput(label = "API key de Anthropic", placeholder = "sk-ant-…", onSave = vm::saveAnthropicKey)
                Text(
                    "Consíguela en console.anthropic.com → API Keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Backup, null, tint = MaterialTheme.colorScheme.primary)
                Text("  Tus datos", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "Un respaldo guarda en un solo archivo tus libros, subrayados, notas, marcadores, colecciones, progreso y " +
                    "estadísticas. Guárdalo en Drive o en tu computadora para no perder nada si cambias de tablet.",
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
                    Text("Crear respaldo")
                }
                OutlinedButton(onClick = { pickBackup.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !dataBusy, shape = CircleShape) {
                    Text("Restaurar")
                }
                OutlinedButton(onClick = { exportHighlights.launch(BackupManager.highlightsFileName()) }, enabled = !dataBusy, shape = CircleShape) {
                    Text("Exportar subrayados")
                }
            }
            if (dataBusy) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  Trabajando…", style = MaterialTheme.typography.bodySmall)
                }
            }
            dataStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
            Text(
                "«Exportar subrayados» crea un archivo Markdown con tus citas y notas, ordenadas por libro y capítulo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Card {
            Text("Privacidad", style = MaterialTheme.typography.titleLarge)
            Text(
                "Tus libros, subrayados y colecciones viven solo en este dispositivo. Las keys se guardan cifradas con el " +
                    "Android Keystore. Al usar la IA, el texto del libro hasta tu posición y tu pregunta se envían al " +
                    "proveedor activo (Google o Anthropic).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Text(
            "Lumen 0.2 · hecho para lectores que olvidan nombres",
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
            description = "Personalizado",
            onClick = { showCustom = true; hue = hueOf(currentColor) },
        )
    }

    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            presetName ?: "Personalizado",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (current != null) TextButton(onClick = { showCustom = false; onPick(null) }) { Text("Restablecer") }
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
            "Desliza para elegir el tono; se ajusta solo para que el texto de los botones siempre se lea bien.",
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
        OutlinedButton(onClick = onRemove, shape = CircleShape) { Text("Quitar") }
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
                    Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Mostrar")
                }
            },
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = { onSave(input.trim()); input = "" }, enabled = input.isNotBlank(), shape = CircleShape) { Text("Guardar") }
    }
}

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
