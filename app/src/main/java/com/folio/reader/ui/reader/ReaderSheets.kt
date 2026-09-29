package com.folio.reader.ui.reader

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Switch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.folio.reader.ai.GeminiService
import com.folio.reader.ai.SpoilerFreeContextBuilder
import com.folio.reader.data.ReaderSettings
import com.folio.reader.data.ReaderTheme
import com.folio.reader.data.db.BookmarkEntity
import com.folio.reader.data.db.HighlightEntity
import com.folio.reader.ui.components.markdownLite
import com.folio.reader.ui.theme.Folio
import com.folio.reader.ui.theme.SerifDisplay

// ---------------------------------------------------------------------------------------------
// Table of contents
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(ui: ReaderUiState, onDismiss: () -> Unit, onSelect: (Int, String?) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Índice", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 24.dp))
        Text(
            "${ui.book?.title.orEmpty()} · ${(ui.progress * 100).toInt()}% leído",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        val entries: List<Triple<String, Int, Pair<Int, String?>>> = if (ui.toc.isNotEmpty()) {
            ui.toc.map { Triple(it.title, it.depth, it.spineIndex to it.fragment) }
        } else {
            ui.chapters.map { Triple(SpoilerFreeContextBuilder.label(it), 0, it.spineIndex to null) }
        }
        val currentIdx = entries.indexOfLast { it.third.first <= ui.spineIndex }.coerceAtLeast(0)
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIdx - 3).coerceAtLeast(0))
        LazyColumn(state = listState, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)) {
            itemsIndexed(entries) { i, (title, depth, target) ->
                val current = i == currentIdx
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(target.first, target.second) }
                        .background(if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .padding(start = (24 + depth * 18).dp, end = 24.dp, top = 14.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        style = if (depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        color = if (current) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (target.first < ui.spineIndex) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Define / translate
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefineSheet(
    state: DefineState,
    onDismiss: () -> Unit,
    onAskMore: () -> Unit,
    onCopy: (String) -> Unit,
    onSaveGeminiKey: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Translate, null, tint = MaterialTheme.colorScheme.primary)
                Text("  Definir", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                state.selection.text.trim().take(120),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = SerifDisplay,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                state.needsKey && state.error != null -> FreeAiSetupCard(onSaveGeminiKey, onOpenSettings)
                state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                state.text.isBlank() && state.streaming -> TypingDots()
                else -> Text(
                    markdownLite(state.text),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                    fontFamily = FontFamily.Serif,
                )
            }
            if (state.error == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAskMore, shape = CircleShape) {
                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(16.dp))
                        Text("  Preguntar más")
                    }
                    if (!state.streaming && state.text.isNotBlank()) {
                        TextButton(onClick = { onCopy(state.text.replace("**", "")) }) { Text("Copiar") }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    state: SearchState,
    onDismiss: () -> Unit,
    onQuery: (String) -> Unit,
    onOnlyUpToHere: (Boolean) -> Unit,
    onOpen: (SearchHit) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(300); runCatching { focus.requestFocus() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.9f).imePadding()) {
            Text("Buscar en el libro", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 24.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                placeholder = { Text("Palabra, nombre o frase…") },
                singleLine = true,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).focusRequester(focus),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOnlyUpToHere(!state.onlyUpToHere) }
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp), tint = if (state.onlyUpToHere) Folio.Mint else MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text("Solo hasta donde voy", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (state.onlyUpToHere) "Sin spoilers: no muestra texto que aún no leíste."
                        else "Buscando en todo el libro: los resultados pueden adelantar la historia.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.onlyUpToHere) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                Switch(checked = state.onlyUpToHere, onCheckedChange = onOnlyUpToHere)
            }
            HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            val status = when {
                state.query.trim().length < 2 -> "Escribe al menos 2 letras."
                state.searching -> "Buscando…"
                state.results.isEmpty() -> "Sin resultados."
                state.truncated -> "Más de ${state.results.size} resultados; mostrando los primeros."
                else -> if (state.results.size == 1) "1 resultado" else "${state.results.size} resultados"
            }
            Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                items(state.results.size) { i ->
                    val hit = state.results[i]
                    val showHeader = i == 0 || state.results[i - 1].spineIndex != hit.spineIndex
                    Column {
                        if (showHeader) {
                            Text(
                                hit.chapterTitle,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 2.dp),
                            )
                        }
                        Text(
                            buildAnnotatedString {
                                append("…"); append(hit.before)
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = Folio.Mustard.copy(alpha = .35f))) { append(hit.match) }
                                append(hit.after); append("…")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(hit) }
                                .padding(horizontal = 24.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Highlights & bookmarks
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationsSheet(
    ui: ReaderUiState,
    onDismiss: () -> Unit,
    onOpen: (Int, Int, Int?) -> Unit,
    onDeleteBookmark: (BookmarkEntity) -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Tus notas", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 24.dp))
        PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent, modifier = Modifier.padding(top = 8.dp)) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Subrayados · ${ui.highlights.size}") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Marcadores · ${ui.bookmarks.size}") })
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 240.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            if (tab == 0) {
                if (ui.highlights.isEmpty()) item { EmptyNote("Mantén presionado el texto y elige «Subrayar» o «Nota».") }
                items(ui.highlights, key = { it.id }) { h ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { onOpen(h.spineIndex, h.startOffset, h.endOffset) }
                            .padding(12.dp)
                    ) {
                        Box(
                            Modifier
                                .width(4.dp)
                                .height(44.dp)
                                .clip(CircleShape)
                                .background(Folio.highlightColors[h.color] ?: Folio.Mustard)
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(
                                h.text,
                                fontFamily = SerifDisplay,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!h.note.isNullOrBlank()) {
                                Text(
                                    h.note,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            Text(
                                chapterName(ui, h.spineIndex),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            } else {
                if (ui.bookmarks.isEmpty()) item { EmptyNote("Toca el ícono de marcador arriba para guardar esta página.") }
                items(ui.bookmarks, key = { it.id }) { b ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { onOpen(b.spineIndex, b.offset, null) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Bookmark, null, tint = MaterialTheme.colorScheme.secondary)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(b.snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${chapterName(ui, b.spineIndex)} · ${(b.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onDeleteBookmark(b) }) { Icon(Icons.Rounded.DeleteOutline, "Quitar") }
                    }
                }
            }
        }
    }
}

private fun chapterName(ui: ReaderUiState, spine: Int) =
    ui.chapters.getOrNull(spine)?.let(SpoilerFreeContextBuilder::label) ?: "Sección ${spine + 1}"

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
    )
}

// ---------------------------------------------------------------------------------------------
// Typography & theme
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderSettingsSheet(settings: ReaderSettings, onDismiss: () -> Unit, onChange: ((ReaderSettings) -> ReaderSettings) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Lectura", style = MaterialTheme.typography.headlineMedium)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tamaño", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                FilledIconButton(onClick = { onChange { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(13)) } }) {
                    Icon(Icons.Rounded.Remove, "Más pequeño")
                }
                Text(
                    "${settings.fontSize}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.widthIn(min = 44.dp).padding(horizontal = 12.dp),
                )
                FilledIconButton(onClick = { onChange { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(34)) } }) {
                    Icon(Icons.Rounded.Add, "Más grande")
                }
            }

            Column {
                Text("Tema", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ReaderTheme.entries.forEach { t ->
                        val selected = t == settings.theme
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onChange { s -> s.copy(theme = t) } }) {
                            Box(
                                Modifier
                                    .size(width = 72.dp, height = 52.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(t.background))
                                    .border(
                                        if (selected) BorderStroke(2.5.dp, MaterialTheme.colorScheme.primary)
                                        else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                        RoundedCornerShape(14.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) { Text("Aa", color = Color(t.foreground), fontFamily = SerifDisplay, fontSize = 20.sp) }
                            Text(t.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }

            OptionRow("Letra", listOf("Serif" to true, "Sans" to false), settings.serif) { v -> onChange { it.copy(serif = v) } }
            OptionRow("Interlineado", listOf("Compacto" to 1.4f, "Normal" to 1.6f, "Amplio" to 1.85f), settings.lineHeight) { v ->
                onChange { it.copy(lineHeight = v) }
            }
            OptionRow("Márgenes", listOf("Estrechos" to 16, "Normales" to 24, "Amplios" to 36), settings.margin) { v ->
                onChange { it.copy(margin = v) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> OptionRow(title: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (label, value) ->
                val isSel = value == selected
                Surface(
                    onClick = { onSelect(value) },
                    shape = CircleShape,
                    color = if (isSel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (isSel) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Ask AI
// ---------------------------------------------------------------------------------------------

/** Suggestions about a selected passage. */
private val selectionQuestions = listOf(
    "¿Quién es y de dónde salió?",
    "¿Qué es esto?",
    "¿Qué significa aquí?",
    "¿Qué ha pasado con esto hasta ahora?",
)

/** Suggestions for the open "¿Dónde me quedé?" chat. Nothing is sent until the reader taps or types. */
private val chatSuggestions = listOf(
    "¿Dónde me quedé?",
    "¿Quiénes son los personajes principales?",
    "¿Qué quedó pendiente?",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AskSheet(
    state: AskState,
    onDismiss: () -> Unit,
    onAsk: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onSaveGeminiKey: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val inputFocus = remember { FocusRequester() }
    // Open chat (no selection): put the cursor in the box so the reader can type right away.
    LaunchedEffect(Unit) {
        if (state.selection == null && state.messages.isEmpty()) {
            kotlinx.coroutines.delay(350) // let the sheet finish opening
            runCatching { inputFocus.requestFocus() }
        }
    }
    val listState = rememberLazyListState()
    val lastLen = state.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(state.messages.size, lastLen) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size + 1)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxHeight(0.88f).imePadding()) {
            // Header
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimary) }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("Pregúntale a Lumen", style = MaterialTheme.typography.titleLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Shield, null, Modifier.size(13.dp), tint = Folio.Mint)
                        Text(
                            " Sin spoilers · solo hasta ${state.positionLabel}" +
                                (state.provider?.let { " · ${it.label}" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Cerrar") }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                state.selection?.let { sel ->
                    item {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Row(Modifier.padding(14.dp)) {
                                Box(Modifier.width(3.dp).height(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary))
                                Text(
                                    "«${sel.text.trim()}»",
                                    fontFamily = SerifDisplay,
                                    fontStyle = FontStyle.Italic,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 6,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                    }
                }
                if (state.messages.isEmpty() && !state.needsKey) {
                    if (state.selection == null) {
                        item {
                            Text(
                                "Pregúntame lo que quieras del libro hasta tu página: qué ha pasado, quién es alguien, " +
                                    "qué significa algo… Sin spoilers.",
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = FontFamily.Serif,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            (if (state.selection != null) selectionQuestions else chatSuggestions).forEach { q ->
                                Surface(
                                    onClick = { onAsk(q) },
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Text(q, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                                }
                            }
                        }
                    }
                }
                items(state.messages.size) { i ->
                    val m = state.messages[i]
                    if (m.fromUser) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            Surface(
                                shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.widthIn(max = 300.dp),
                            ) { Text(m.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
                        }
                    } else if (m.text.isBlank() && state.streaming) {
                        TypingDots()
                    } else {
                        Text(
                            markdownLite(m.text),
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                            fontFamily = FontFamily.Serif,
                        )
                    }
                }
                state.note?.let { item { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                when {
                    // First run: no provider yet → offer the free tier right here.
                    state.needsKey && state.provider == null -> item { FreeAiSetupCard(onSaveGeminiKey, onOpenSettings) }
                    state.quotaExceeded -> item {
                        NoticeCard(
                            text = state.error.orEmpty(),
                            action = "Agregar mi key de Anthropic",
                            onAction = onOpenSettings,
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    state.error != null -> item {
                        NoticeCard(
                            text = state.error,
                            action = if (state.needsKey) "Ir a Ajustes" else null,
                            onAction = onOpenSettings,
                            container = MaterialTheme.colorScheme.errorContainer,
                            content = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                item { Spacer(Modifier.height(4.dp)) }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(if (state.messages.isEmpty()) "Escribe tu pregunta…" else "Pregunta algo más…") },
                    shape = CircleShape,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank()) { onAsk(input); input = "" } }),
                    modifier = Modifier.weight(1f).focusRequester(inputFocus),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { onAsk(input); input = "" },
                    enabled = input.isNotBlank() && !state.streaming,
                    modifier = Modifier.size(52.dp),
                ) { Icon(Icons.AutoMirrored.Rounded.Send, "Enviar") }
            }
        }
    }
}

@Composable
private fun NoticeCard(text: String, action: String?, onAction: () -> Unit, container: Color, content: Color) {
    Surface(shape = MaterialTheme.shapes.medium, color = container) {
        Column(Modifier.padding(16.dp)) {
            Text(text, color = content, style = MaterialTheme.typography.bodyMedium)
            if (action != null) TextButton(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) { Text(action) }
        }
    }
}

/** Three-step setup for the free Gemini tier, without leaving the book. */
@Composable
fun FreeAiSetupCard(onSaveGeminiKey: (String) -> Unit, onOpenSettings: (() -> Unit)?) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Activa la IA gratis", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "Lumen usa Google Gemini en su plan gratuito. Solo necesitas una key gratis de Google (sin tarjeta):",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "1. Abre Google AI Studio e inicia sesión con tu cuenta de Google.\n" +
                    "2. Toca «Create API key» y cópiala.\n" +
                    "3. Pégala aquí abajo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            OutlinedButton(
                onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GeminiService.API_KEY_URL))) } },
                shape = CircleShape,
            ) { Text("Abrir Google AI Studio") }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                placeholder = { Text("Pega tu key de Gemini") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { onSaveGeminiKey(key.trim()); key = "" }, enabled = key.isNotBlank(), shape = CircleShape) {
                Text("Guardar y continuar")
            }
            Text(
                "Privacidad: en el plan gratis, Google puede usar el texto enviado para mejorar sus productos. " +
                    "Si prefieres que no, usa tu propia key de Anthropic (de pago).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f),
            )
            if (onOpenSettings != null) {
                TextButton(onClick = onOpenSettings) { Text("¿Ya tienes key de Anthropic? Ajustes") }
            }
        }
    }
}

@Composable
private fun TypingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = .25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(520, delayMillis = i * 160), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(9.dp).alpha(a).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
        Text(
            "  Releyendo hasta tu página…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Highlight editor
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightEditorSheet(
    highlight: HighlightEntity,
    focusNote: Boolean,
    onDismiss: () -> Unit,
    onSave: (HighlightEntity) -> Unit,
    onDelete: (HighlightEntity) -> Unit,
    onAsk: (HighlightEntity) -> Unit,
) {
    var color by remember(highlight.id) { mutableStateOf(highlight.color) }
    var note by remember(highlight.id) { mutableStateOf(highlight.note.orEmpty()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusNote) { if (focusNote) runCatching { focus.requestFocus() } }

    ModalBottomSheet(onDismissRequest = { onSave(highlight.copy(color = color, note = note.ifBlank { null })) }) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "«${highlight.text}»",
                fontFamily = SerifDisplay,
                fontStyle = FontStyle.Italic,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Folio.highlightColors.forEach { (name, c) ->
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(if (name == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { color = name },
                    )
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text("Agrega una nota…") },
                shape = MaterialTheme.shapes.medium,
                minLines = 2,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onAsk(highlight) }) {
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp))
                    Text("  Preguntar a la IA")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onDelete(highlight) }) { Icon(Icons.Rounded.DeleteOutline, "Eliminar") }
                TextButton(onClick = { onSave(highlight.copy(color = color, note = note.ifBlank { null })) }) { Text("Guardar") }
            }
        }
    }
}
