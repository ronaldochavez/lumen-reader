package com.folio.reader.ui.encyclopedia

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.reader.AppContainer
import com.folio.reader.ai.AiService
import com.folio.reader.ai.Encyclopedia
import com.folio.reader.ai.LoreEntry
import com.folio.reader.container
import com.folio.reader.data.db.BookEntity
import com.folio.reader.data.db.EncyclopediaEntity
import com.folio.reader.ui.theme.Folio
import com.folio.reader.ui.theme.SerifDisplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

import com.folio.reader.ai.LoreKind

private data class Kind(val id: String, val label: String, val singular: String, val icon: ImageVector)

private val kinds = listOf(
    Kind(LoreKind.PLACE, "Places", "Place", Icons.Rounded.Place),
    Kind(LoreKind.OBJECT, "Objects", "Object", Icons.Rounded.Category),
    Kind(LoreKind.TERM, "Terms", "Term", Icons.Rounded.Lightbulb),
    Kind(LoreKind.FACTION, "Factions", "Faction", Icons.Rounded.Groups),
    Kind(LoreKind.EVENT, "Events", "Event", Icons.Rounded.Event),
)

private fun kindOf(id: String) = kinds.firstOrNull { it.id == LoreKind.canonical(id) } ?: kinds[2]

data class EncyclopediaUiState(
    val book: BookEntity? = null,
    val data: Encyclopedia? = null,
    val generatedAt: String? = null,
    val generatedProgress: Float = 0f,
    val currentLabel: String = "",
    val currentProgress: Float = 0f,
    val loading: Boolean = false,
    val error: String? = null,
    val needsKey: Boolean = false,
) {
    val outdated get() = data != null && currentProgress - generatedProgress > 0.01f
    /** Built further into the book than the reader is now (they jumped back): could spoil. */
    val aheadOfReader get() = data != null && generatedProgress - currentProgress > 0.01f
}

class EncyclopediaViewModel(savedState: SavedStateHandle, private val c: AppContainer) : ViewModel() {
    private val bookId: String = checkNotNull(savedState["bookId"])
    private val _state = MutableStateFlow(EncyclopediaUiState())
    val state = _state.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    init {
        viewModelScope.launch {
            val book = c.books.get(bookId) ?: return@launch
            val snap = c.snapshots.get(bookId)
            val spine = snap?.spineIndex ?: book.spineIndex
            val progress = snap?.progress ?: book.progress
            val label = c.books.positionLabel(bookId, spine, snap?.textUpToPage?.length ?: book.chapterOffset, progress)
            _state.update { it.copy(book = book, currentProgress = progress, currentLabel = label) }
        }
        viewModelScope.launch {
            c.db.encyclopedias().observe(bookId).collect { saved ->
                if (saved == null) return@collect
                val data = runCatching { json.decodeFromString(Encyclopedia.serializer(), saved.json) }.getOrNull()
                _state.update { it.copy(data = data, generatedAt = saved.positionLabel, generatedProgress = saved.progress) }
            }
        }
    }

    fun generate() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null, needsKey = false) }
        viewModelScope.launch {
            try {
                val book = c.books.get(bookId) ?: error("Book not found")
                val chapters = c.books.chapters(bookId)
                val snap = c.snapshots.get(bookId)
                val spine = snap?.spineIndex ?: book.spineIndex
                val progress = snap?.progress ?: book.progress
                val ctx = c.contextBuilder.build(book, chapters, spine, progress, snap?.textUpToPage, c.ai.maxContextChars())
                val data = c.ai.extractEncyclopedia(ctx)
                c.db.encyclopedias().upsert(
                    EncyclopediaEntity(bookId, json.encodeToString(Encyclopedia.serializer(), data), spine, progress, ctx.positionLabel, System.currentTimeMillis())
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = AiService.friendlyError(e), needsKey = AiService.needsSetup(e)) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory { initializer { EncyclopediaViewModel(createSavedStateHandle(), container) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncyclopediaScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: EncyclopediaViewModel = viewModel(factory = EncyclopediaViewModel.Factory),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    var reveal by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Encyclopedia", style = MaterialTheme.typography.headlineSmall)
                        Text(s.book?.title.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (s.data != null && !s.loading) IconButton(onClick = vm::generate) { Icon(Icons.Rounded.Refresh, "Update to my page") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val data = s.data
            when {
                s.loading -> Centered {
                    CircularProgressIndicator()
                    Text("Cataloging places, objects and terms…", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp))
                    Text("Up to ${s.currentLabel}. Long books can take a minute or two.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                }
                data == null -> Centered {
                    Box(Modifier.size(104.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.secondary)
                    }
                    Text("The world of the book", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 24.dp))
                    Text(
                        "Places, objects, terms, factions and events, each with its own entry. Only from what you've read.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(onClick = vm::generate, shape = CircleShape, modifier = Modifier.padding(top = 24.dp)) {
                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text("Create encyclopedia")
                    }
                    Text(s.currentLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    s.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
                        if (s.needsKey) TextButton(onClick = onOpenSettings) { Text("Go to Settings") }
                    }
                }
                s.aheadOfReader && !reveal -> Centered {
                    Icon(Icons.Rounded.Shield, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.secondary)
                    Text("Careful: possible spoilers", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                    Text(
                        "The saved encyclopedia goes up to ${s.generatedAt}, past where you are now (${s.currentLabel}).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(onClick = vm::generate, shape = CircleShape, modifier = Modifier.padding(top = 20.dp)) { Text("Rebuild up to my page") }
                    OutlinedButton(onClick = { reveal = true }, shape = CircleShape, modifier = Modifier.padding(top = 8.dp)) { Text("Show it anyway") }
                }
                else -> EntryList(s, data, vm::generate)
            }
        }
    }
}

@Composable
private fun EntryList(s: EncyclopediaUiState, data: Encyclopedia, onRegenerate: () -> Unit) {
    var filter by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    val q = query.trim().lowercase()
    val visible = data.entries
        .filter { filter == null || it.kindKey == filter }
        .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.aliases.any { a -> a.lowercase().contains(q) } }
        .sortedWith(compareByDescending<LoreEntry> { it.importance }.thenBy { it.name })

    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Surface(
                color = if (s.outdated) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Shield, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        if (s.outdated) "  Up to ${s.generatedAt}. You've read further since." else "  No spoilers · up to ${s.generatedAt}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (s.outdated) TextButton(onClick = onRegenerate) { Text("Update") }
                }
            }
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search the encyclopedia…") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(kinds.filter { k -> data.entries.any { it.kindKey == k.id } }) { k ->
                    FilterChip(
                        selected = filter == k.id,
                        onClick = { filter = if (filter == k.id) null else k.id },
                        label = { Text("${k.label} · ${data.entries.count { it.kindKey == k.id }}") },
                        leadingIcon = { Icon(k.icon, null, Modifier.size(16.dp)) },
                        shape = CircleShape,
                    )
                }
            }
        }
        if (visible.isEmpty()) item {
            Text("Nothing here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        items(visible, key = { it.id }) { e ->
            EntryCard(e, expanded == e.id) { expanded = if (expanded == e.id) null else e.id }
        }
    }
}

@Composable
private fun EntryCard(e: LoreEntry, open: Boolean, onToggle: () -> Unit) {
    val kind = kindOf(e.kind)
    Surface(onClick = onToggle, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().animateContentSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(kind.icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(e.name, style = MaterialTheme.typography.titleMedium, fontFamily = SerifDisplay)
                    Text(
                        kind.singular +
                            (e.firstAppearance.takeIf { it.isNotBlank() }?.let { " · first in $it" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(e.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
            if (open) {
                if (e.aliases.isNotEmpty()) {
                    Text("Also: " + e.aliases.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
                if (e.facts.isNotEmpty()) {
                    Text("What we know", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    e.facts.forEach { f -> Text("•  $f", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
                }
                if (e.related.isNotEmpty()) {
                    Text("Related to: " + e.related.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
                }
            } else if (e.facts.isNotEmpty() || e.related.isNotEmpty()) {
                Text("Show more", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}
