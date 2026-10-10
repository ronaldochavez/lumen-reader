package com.folio.reader.ui.graph

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.folio.reader.ai.GraphCharacter
import com.folio.reader.ai.GraphData
import com.folio.reader.ai.GraphRelation
import com.folio.reader.ui.theme.Folio
import com.folio.reader.ui.theme.SerifDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue
import kotlin.math.hypot
import kotlin.math.min

import com.folio.reader.ai.RelationType
import com.folio.reader.ui.reader.SearchController
import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.draw.clipToBounds

private val relationTypes = linkedMapOf(
    RelationType.FAMILY to ("Family" to Color(0xFFF2765A)),
    RelationType.ROMANCE to ("Romance" to Color(0xFFE07BA5)),
    RelationType.FRIENDSHIP to ("Friendship" to Color(0xFF4FAE8A)),
    RelationType.ALLIANCE to ("Alliance" to Color(0xFF6C93DB)),
    RelationType.RIVALRY to ("Rivalry" to Color(0xFFC8423B)),
    RelationType.WORK to ("Work" to Color(0xFFD9A232)),
    RelationType.OTHER to ("Other" to Color(0xFF9A8FA0)),
)

private fun relationColor(type: String) = relationTypes[RelationType.canonical(type)]?.second ?: Color(0xFF9A8FA0)

private val groupColors = listOf(
    Color(0xFF5B3E96), Color(0xFF2F7D6D), Color(0xFF34457A), Color(0xFF8C3B5E),
    Color(0xFF6B5A2E), Color(0xFFB4533A), Color(0xFF3E6E8C), Color(0xFF7A4FA3),
)

private fun groupColor(group: String) =
    if (group.isBlank()) Color(0xFF6F6273) else groupColors[group.lowercase().hashCode().absoluteValue % groupColors.size]

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterGraphScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: GraphViewModel = viewModel(factory = GraphViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var revealAhead by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var selected by remember(state.graph) { mutableStateOf<String?>(null) }
    // Bumped to move the map's camera onto [selected] (when picked from a list, not tapped on the map).
    var focusTick by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    fun showOnMap(id: String) {
        selected = id
        tab = 0
        focusTick++
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Characters", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            state.book?.title.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (state.graph != null && !state.loading) {
                        IconButton(onClick = { picking = true }) { Icon(Icons.Rounded.PersonSearch, "Find a character") }
                        IconButton(onClick = vm::generate) { Icon(Icons.Rounded.Refresh, "Update to my page") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val graph = state.graph
            when {
                state.loading -> GeneratingState(state.currentLabel)
                graph == null -> EmptyGraphState(state, vm::generate, onOpenSettings)
                state.aheadOfReader && !revealAhead -> AheadWarning(state, onRegenerate = vm::generate, onReveal = { revealAhead = true })
                else -> Column(Modifier.fillMaxSize()) {
                    StatusBanner(state, vm::generate)
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
                    }
                    SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth()) {
                        listOf("Map", "List").forEachIndexed { i, label ->
                            SegmentedButton(
                                selected = tab == i,
                                onClick = { tab = i },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                            ) { Text(label) }
                        }
                    }
                    if (tab == 0) {
                        GraphMap(
                            graph = graph,
                            selected = selected,
                            onSelect = { selected = it },
                            focusTick = focusTick,
                            onFind = { picking = true },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        CharacterList(graph, onShowOnMap = ::showOnMap, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    val graph = state.graph
    if (picking && graph != null) {
        CharacterPicker(graph, onPick = { picking = false; showOnMap(it) }, onDismiss = { picking = false })
    }
}

/** Characters sorted by importance, filtered by name, alias or group (ignoring case and accents). */
private fun searchCharacters(graph: GraphData, query: String): List<GraphCharacter> {
    val q = SearchController.fold(query.trim())
    return graph.characters
        .filter { ch -> q.isEmpty() || (listOf(ch.name, ch.group) + ch.aliases).any { SearchController.fold(it).contains(q) } }
        .sortedWith(compareByDescending<GraphCharacter> { it.importance }.thenBy { it.name.lowercase() })
}

private fun initialsOf(name: String) =
    name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

/** A searchable list of everyone on the map: for crowded books, finding a person by name beats hunting for a dot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CharacterPicker(graph: GraphData, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember(graph, query) { searchCharacters(graph, query) }
    val degree = remember(graph) {
        graph.relationships.flatMap { listOf(it.source, it.target) }.groupingBy { it }.eachCount()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Text("Find a character", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Name, nickname or family…") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            )
            Text(
                if (results.size == 1) "1 character" else "${results.size} characters",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 24.dp)) {
                items(results, key = { it.id }) { ch ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { onPick(ch.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(groupColor(ch.group)), contentAlignment = Alignment.Center) {
                            Text(initialsOf(ch.name), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        }
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(ch.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            val sub = listOfNotNull(
                                ch.group.takeIf { it.isNotBlank() },
                                ch.aliases.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "also "),
                            ).joinToString(" · ")
                            if (sub.isNotEmpty()) {
                                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                        val n = degree[ch.id] ?: 0
                        Text(
                            if (n == 1) "1 link" else "$n links",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(state: GraphUiState, onRegenerate: () -> Unit) {
    Surface(
        color = if (state.outdated) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                if (state.outdated) "  Map up to ${state.generatedAt}. You've read further since."
                else "  No spoilers · up to ${state.generatedAt}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            if (state.outdated) TextButton(onClick = onRegenerate) { Text("Update") }
        }
    }
}

@Composable
private fun GraphMap(
    graph: GraphData,
    selected: String?,
    onSelect: (String?) -> Unit,
    focusTick: Int,
    onFind: () -> Unit,
    modifier: Modifier,
) {
    val layout by produceState<Map<String, Offset>?>(null, graph) {
        value = withContext(Dispatchers.Default) { ForceLayout.compute(graph) }
    }
    var filter by remember(graph) { mutableStateOf<String?>(null) }
    val byId = remember(graph) { graph.characters.associateBy { it.id } }
    val presentTypes = remember(graph) { relationTypes.keys.filter { t -> graph.relationships.any { it.kind == t } } }

    Column(modifier) {
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                AssistChip(
                    onClick = onFind,
                    label = { Text("Find") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) },
                    shape = CircleShape,
                )
            }
            items(presentTypes) { t ->
                val (label, color) = relationTypes.getValue(t)
                FilterChip(
                    selected = filter == t,
                    onClick = { filter = if (filter == t) null else t },
                    label = { Text(label) },
                    leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(color)) },
                    shape = CircleShape,
                )
            }
        }
        // clipToBounds: zoomed or panned, the drawing must stay inside the map area instead of covering the chips,
        // the Map/List switch and the top bar.
        Box(Modifier.fillMaxSize().padding(top = 8.dp).clipToBounds()) {
            val positions = layout
            if (positions != null) {
                GraphCanvas(graph, positions, selected, filter, focusTick, onSelect = onSelect, modifier = Modifier.fillMaxSize())
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = selected != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                selected?.let { id -> byId[id] }?.let { ch ->
                    CharacterCard(ch, graph, byId, onClose = { onSelect(null) }, onSelect = onSelect)
                }
            }
        }
    }
}

@Composable
private fun GraphCanvas(
    graph: GraphData,
    positions: Map<String, Offset>,
    selected: String?,
    filter: String?,
    focusTick: Int,
    onSelect: (String?) -> Unit,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val selectionRing = MaterialTheme.colorScheme.primary.copy(alpha = .35f)
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val importance = remember(graph) { graph.characters.associate { it.id to it.importance.coerceIn(1, 5) } }
    val chars = graph.characters
    val labelColor = MaterialTheme.colorScheme.onBackground
    val edgeLabelBg = MaterialTheme.colorScheme.background

    fun radius(id: String) = ForceLayout.nodeRadius(importance[id] ?: 3)

    // Fit the whole graph on screen once we know the canvas size.
    LaunchedEffect(positions, canvasSize) {
        if (canvasSize == IntSize.Zero || positions.isEmpty()) return@LaunchedEffect
        val xs = positions.values.map { it.x }
        val ys = positions.values.map { it.y }
        val bw = (xs.max() - xs.min()).coerceAtLeast(1f) + 160f
        val bh = (ys.max() - ys.min()).coerceAtLeast(1f) + 160f
        val s = min(canvasSize.width / bw, canvasSize.height / bh).coerceIn(0.3f, 2.5f)
        val cx = (xs.max() + xs.min()) / 2
        val cy = (ys.max() + ys.min()) / 2
        scale = s
        pan = Offset(canvasSize.width / 2f - cx * s, canvasSize.height / 2f - cy * s)
    }

    // Picked from the list: glide to that character, zooming in if the map is far out. It's placed in the upper
    // part of the map because the character card covers the bottom.
    LaunchedEffect(focusTick, positions, canvasSize) {
        val target = selected?.let { positions[it] } ?: return@LaunchedEffect
        if (focusTick == 0 || canvasSize == IntSize.Zero) return@LaunchedEffect
        val fromScale = scale
        val fromPan = pan
        val toScale = maxOf(scale, 1.2f)
        val toPan = Offset(canvasSize.width / 2f - target.x * toScale, canvasSize.height * 0.32f - target.y * toScale)
        animate(0f, 1f, animationSpec = tween(450)) { t, _ ->
            scale = fromScale + (toScale - fromScale) * t
            pan = fromPan + (toPan - fromPan) * t
        }
    }

    val neighbors = remember(selected, graph) {
        if (selected == null) emptySet()
        else graph.relationships.filter { it.source == selected || it.target == selected }
            .flatMap { listOf(it.source, it.target) }.toSet()
    }

    Canvas(
        modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, panChange, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(0.25f, 4f)
                    pan = centroid - (centroid - pan) * (newScale / scale) + panChange
                    scale = newScale
                }
            }
            .pointerInput(positions) {
                detectTapGestures { tap ->
                    val world = (tap - pan) / scale
                    val hit = positions.entries
                        .map { (id, p) -> id to hypot(p.x - world.x, p.y - world.y) }
                        .filter { (id, d) -> d <= radius(id) + 14f }
                        .minByOrNull { it.second }
                    onSelect(hit?.first)
                }
            }
    ) {
        // Labels of the selected character's relationships, drawn later in screen space (see the names below).
        val edgeLabels = mutableListOf<GraphRelation>()
        withTransform({
            translate(pan.x, pan.y)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            // Edges
            for (r in graph.relationships) {
                val a = positions[r.source] ?: continue
                val b = positions[r.target] ?: continue
                val focused = selected == null || r.source == selected || r.target == selected
                val typeMatch = filter == null || r.kind == filter
                val alpha = when {
                    !typeMatch -> 0.06f
                    !focused -> 0.1f
                    else -> 0.85f
                }
                drawLine(
                    color = relationColor(r.type).copy(alpha = alpha),
                    start = a,
                    end = b,
                    strokeWidth = if (selected != null && focused && typeMatch) 5f else 3f,
                    pathEffect = if (r.kind == RelationType.RIVALRY) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null,
                )
                if (selected != null && focused && typeMatch && r.label.isNotBlank()) edgeLabels += r
            }
            // Nodes
            for (ch in chars) {
                val p = positions[ch.id] ?: continue
                val r = radius(ch.id)
                val dim = selected != null && ch.id !in neighbors && ch.id != selected
                val alpha = if (dim) 0.25f else 1f
                val color = groupColor(ch.group)
                if (ch.id == selected) drawCircle(selectionRing, r + 10f, p)
                drawCircle(color.copy(alpha = alpha), r, p)
                drawCircle(Color.White.copy(alpha = .9f * alpha), r, p, style = Stroke(width = 3f))
                val initials = ch.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
                val init = measurer.measure(initials, TextStyle(fontSize = (r / 2.4f).sp, color = Color.White.copy(alpha = alpha), fontWeight = FontWeight.Bold))
                drawText(init, topLeft = p - Offset(init.size.width / 2f, init.size.height / 2f))
            }
        }

        // Names, drawn at a fixed screen size like a map's place names: zooming in spreads the circles apart and
        // reveals more of them. Placed by priority (the selected character and its links, then importance); a
        // name that would collide with one already placed is skipped.
        val placed = mutableListOf<androidx.compose.ui.geometry.Rect>()
        val order = chars.sortedWith(
            compareByDescending<GraphCharacter> { it.id == selected }
                .thenByDescending { it.id in neighbors }
                .thenByDescending { importance[it.id] ?: 3 },
        )
        for (ch in order) {
            val world = positions[ch.id] ?: continue
            val dim = selected != null && ch.id !in neighbors && ch.id != selected
            val major = (importance[ch.id] ?: 3) >= 4
            val layout = measurer.measure(
                shortName(ch.name),
                TextStyle(
                    fontSize = if (major) 13.sp else 11.sp,
                    color = labelColor.copy(alpha = if (dim) 0.3f else 1f),
                    fontWeight = if (major || ch.id == selected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                ),
            )
            val p = world * scale + pan
            val top = p.y + radius(ch.id) * scale + 4f
            val rect = androidx.compose.ui.geometry.Rect(p.x - layout.size.width / 2f, top, p.x + layout.size.width / 2f, top + layout.size.height)
            if (ch.id != selected && placed.any { it.overlaps(rect) }) continue
            placed += rect
            drawRoundRect(
                color = edgeLabelBg.copy(alpha = 0.7f),
                topLeft = rect.topLeft - Offset(4f, 1f),
                size = androidx.compose.ui.geometry.Size(rect.width + 8f, rect.height + 2f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
            )
            drawText(layout, topLeft = rect.topLeft)
        }

        // Relationship labels at their edge's midpoint, skipped where they'd cover a name or another label.
        for (r in edgeLabels) {
            val a = positions[r.source] ?: continue
            val b = positions[r.target] ?: continue
            val mid = (a + b) / 2f * scale + pan
            val layout = measurer.measure(r.label, TextStyle(fontSize = 11.sp, color = relationColor(r.type), fontWeight = FontWeight.SemiBold))
            val rect = androidx.compose.ui.geometry.Rect(
                mid.x - layout.size.width / 2f, mid.y - layout.size.height / 2f,
                mid.x + layout.size.width / 2f, mid.y + layout.size.height / 2f,
            )
            if (placed.any { it.overlaps(rect) }) continue
            placed += rect
            drawRoundRect(
                color = edgeLabelBg.copy(alpha = .92f),
                topLeft = rect.topLeft - Offset(6f, 2f),
                size = androidx.compose.ui.geometry.Size(rect.width + 12f, rect.height + 4f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f),
            )
            drawText(layout, topLeft = rect.topLeft)
        }
    }
}

/** "Rodion Romanovich Raskolnikov" -> "Rodion Raskolnikov": first and last name; the card shows the full one. */
private fun shortName(name: String): String {
    val words = name.split(' ').filter { it.isNotBlank() }
    return if (words.size >= 3) "${words.first()} ${words.last()}" else name
}

@Composable
private fun CharacterCard(
    ch: GraphCharacter,
    graph: GraphData,
    byId: Map<String, GraphCharacter>,
    onClose: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val relations = graph.relationships.filter { it.source == ch.id || it.target == ch.id }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 12.dp,
        modifier = Modifier.padding(12.dp).navigationBarsPadding().fillMaxWidth().heightIn(max = 340.dp),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(groupColor(ch.group)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        ch.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(ch.name, style = MaterialTheme.typography.titleLarge)
                    val meta = listOfNotNull(ch.group.takeIf { it.isNotBlank() }, ch.firstAppearance.takeIf { it.isNotBlank() }?.let { "first in $it" })
                    if (meta.isNotEmpty()) {
                        Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close") }
            }
            if (ch.aliases.isNotEmpty()) {
                Text(
                    "Also: " + ch.aliases.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Text(ch.description, style = MaterialTheme.typography.bodyLarge, fontFamily = SerifDisplay, modifier = Modifier.padding(top = 10.dp))
            if (relations.isNotEmpty()) {
                Text("Relationships", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                relations.forEach { r -> RelationRow(ch.id, r, byId, onSelect) }
            }
        }
    }
}

@Composable
private fun RelationRow(selfId: String, r: GraphRelation, byId: Map<String, GraphCharacter>, onSelect: (String) -> Unit) {
    val otherId = if (r.source == selfId) r.target else r.source
    val other = byId[otherId] ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { onSelect(otherId) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(relationColor(r.type)))
        Column(Modifier.padding(start = 10.dp)) {
            Text(
                "${other.name} — ${r.label.ifBlank { relationTypes[r.kind]?.first.orEmpty() }}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (r.description.isNotBlank()) {
                Text(r.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CharacterList(graph: GraphData, onShowOnMap: (String) -> Unit, modifier: Modifier) {
    val byId = remember(graph) { graph.characters.associateBy { it.id } }
    var expanded by remember(graph) { mutableStateOf<String?>(null) }
    var query by remember(graph) { mutableStateOf("") }
    val results = remember(graph, query) { searchCharacters(graph, query) }
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search characters…") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (results.isEmpty()) {
            item {
                Text("Nobody by that name so far.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            }
        }
        items(results, key = { it.id }) { ch ->
            Surface(
                onClick = { expanded = if (expanded == ch.id) null else ch.id },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(groupColor(ch.group)))
                        Text("  ${ch.name}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("★".repeat(ch.importance.coerceIn(1, 5)), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                        IconButton(onClick = { onShowOnMap(ch.id) }) { Icon(Icons.Rounded.MyLocation, "Show on the map") }
                    }
                    Text(ch.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 12.dp))
                    if (expanded == ch.id) {
                        graph.relationships.filter { it.source == ch.id || it.target == ch.id }.forEach { r ->
                            RelationRow(ch.id, r, byId) { expanded = it }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneratingState(label: String) {
    val t = rememberInfiniteTransition(label = "gen")
    val pulse by t.animateFloat(0.85f, 1.1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    val messages = listOf("Rereading up to your page…", "Working out who's who…", "Connecting the threads…", "Not peeking past your page…")
    val idx by t.animateFloat(0f, messages.size.toFloat(), infiniteRepeatable(tween(messages.size * 2600)), label = "msg")
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(
            Modifier.size((96 * pulse).dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Hub, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary) }
        Text(
            messages[idx.toInt().coerceIn(0, messages.lastIndex)],
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 28.dp),
            textAlign = TextAlign.Center,
        )
        Text(
            "Up to $label. Long books can take a minute or two.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun EmptyGraphState(state: GraphUiState, onGenerate: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(104.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Hub, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.secondary) }
        Text("Who's who?", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 24.dp))
        Text(
            "Lumen reads the book up to your page and maps the characters and how they're connected. Nothing from what comes later.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGenerate, shape = CircleShape) {
            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Create map up to ${state.currentLabel.substringBefore(" ·")}")
        }
        Text(
            state.currentLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
            if (state.needsKey) TextButton(onClick = onOpenSettings) { Text("Go to Settings") }
        }
    }
}

@Composable
private fun AheadWarning(state: GraphUiState, onRegenerate: () -> Unit, onReveal: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Shield, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.secondary)
        Text("Careful: possible spoilers", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
        Text(
            "The saved map goes up to ${state.generatedAt}, past where you are now (${state.currentLabel}).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onRegenerate, shape = CircleShape, modifier = Modifier.padding(top = 20.dp)) { Text("Rebuild up to my page") }
        OutlinedButton(onClick = onReveal, shape = CircleShape, modifier = Modifier.padding(top = 8.dp)) { Text("Show it anyway") }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
    }
}
