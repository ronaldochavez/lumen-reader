package com.folio.reader.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.rounded.LocalFireDepartment
import com.folio.reader.data.StatsSummary
import com.folio.reader.data.db.BookEntity
import com.folio.reader.data.db.BookFormat
import com.folio.reader.data.db.CollectionWithBooks
import com.folio.reader.ui.components.BookCover
import com.folio.reader.ui.components.ProgressBar
import java.util.Calendar

@Composable
fun LibraryScreen(
    onOpenBook: (String) -> Unit,
    onOpenStats: () -> Unit,
    onFindBooks: () -> Unit,
    vm: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.import(it) }
    var collectionSheetFor by remember { mutableStateOf<BookEntity?>(null) }
    var confirmDelete by remember { mutableStateOf<BookEntity?>(null) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                // Any file: many e-book types have no registered MIME type; the importer sniffs the format.
                onClick = { picker.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Import book") },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Header(state.books.size, state.importing, onFindBooks)
                    if (state.books.isNotEmpty()) StreakPill(stats, onOpenStats)
                }
            }

            state.continueReading?.takeIf { state.filter == null }?.let { book ->
                item(span = { GridItemSpan(maxLineSpan) }) { ContinueReadingCard(book) { onOpenBook(book.id) } }
            }

            if (state.collections.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    CollectionFilters(state.collections, state.filter, vm::setFilter)
                }
            }

            if (state.loaded && state.books.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyLibrary(filtered = state.filter != null, onFindBooks) }
            }

            items(state.books, key = { it.id }) { book ->
                BookTile(
                    book = book,
                    onOpen = { onOpenBook(book.id) },
                    onAddToCollection = { collectionSheetFor = book },
                    onDelete = { confirmDelete = book },
                )
            }
        }
    }

    collectionSheetFor?.let { book ->
        AddToCollectionDialog(book, state.collections, vm) { collectionSheetFor = null }
    }
    confirmDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete book?") },
            text = { Text("\"${book.title}\" and its highlights and bookmarks will be deleted. This can't be undone.") },
            confirmButton = { TextButton(onClick = { vm.delete(book); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(count: Int, importing: Int, onFindBooks: () -> Unit) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
    Column(Modifier.statusBarsPadding().padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                greeting,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = onFindBooks) {
                Icon(Icons.Rounded.TravelExplore, null, Modifier.size(18.dp))
                Text("  Free books")
            }
        }
        Text("Your library", style = MaterialTheme.typography.displaySmall)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text(
                if (count == 1) "1 book" else "$count books",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (importing > 0) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    "  Importing…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StreakPill(s: StatsSummary, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.padding(top = 14.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LocalFireDepartment, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.secondary)
            Text(
                (if (s.streak > 0) "  ${s.streak}-day streak" else "  Start your streak") +
                    "  ·  today ${s.todayMinutes} of ${s.goalMinutes} min",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun ContinueReadingCard(book: BookEntity, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(book, Modifier.width(78.dp), corner = 10.dp)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "CONTINUE READING",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    book.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .7f),
                    maxLines = 1,
                )
                Spacer(Modifier.height(14.dp))
                ProgressBar(book.progress, height = 6.dp)
                Text(
                    "${(book.progress * 100).toInt()}% read",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .7f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun CollectionFilters(collections: List<CollectionWithBooks>, selected: Long?, onSelect: (Long?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("All") },
                shape = CircleShape,
                colors = chipColors(),
            )
        }
        items(collections, key = { it.collection.id }) { c ->
            FilterChip(
                selected = selected == c.collection.id,
                onClick = { onSelect(c.collection.id) },
                label = { Text("${c.collection.name} · ${c.books.size}") },
                shape = CircleShape,
                colors = chipColors(),
            )
        }
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.onBackground,
    selectedLabelColor = MaterialTheme.colorScheme.background,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(book: BookEntity, onOpen: () -> Unit, onAddToCollection: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
    ) {
        Box {
            BookCover(book, Modifier.fillMaxWidth())
            val badge = when (BookFormat.of(book.format)) {
                BookFormat.PDF -> "PDF"
                BookFormat.COMIC -> "COMIC"
                BookFormat.REFLOW -> null
            }
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = .9f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Add to collection") },
                    leadingIcon = { Icon(Icons.Rounded.CollectionsBookmark, null) },
                    onClick = { menu = false; onAddToCollection() },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
        Text(
            book.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            book.author,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (book.progress > 0.005f) {
            ProgressBar(book.progress, Modifier.padding(top = 8.dp), height = 4.dp)
        } else if (book.lastOpenedAt == null) {
            Text(
                "New",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondary,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun EmptyLibrary(filtered: Boolean, onFindBooks: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.secondary)
        }
        Text(
            if (filtered) "This collection is empty" else "Your shelf is waiting",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            if (filtered) "Long-press a book to add it here."
            else "Import a book to get started: EPUB, PDF, MOBI/AZW3, FB2, DOCX, TXT or CBZ/CBR comics. While you read, select any name and ask the AI — no spoilers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (!filtered) {
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    "  Character map included",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            FilledTonalButton(onClick = onFindBooks, modifier = Modifier.padding(top = 20.dp)) {
                Icon(Icons.Rounded.TravelExplore, null, Modifier.size(18.dp))
                Text("  Find free classics")
            }
        }
    }
}

@Composable
private fun AddToCollectionDialog(
    book: BookEntity,
    collections: List<CollectionWithBooks>,
    vm: LibraryViewModel,
    onDismiss: () -> Unit,
) {
    var member by remember { mutableStateOf<Set<Long>?>(null) }
    var newName by remember { mutableStateOf("") }
    LaunchedEffect(book.id) { member = vm.collectionIdsFor(book.id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Collections") },
        text = {
            Column {
                Text(book.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                collections.forEach { c ->
                    val checked = member?.contains(c.collection.id) == true
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable {
                                vm.setMembership(book.id, c.collection.id, !checked)
                                member = member.orEmpty().let { if (checked) it - c.collection.id else it + c.collection.id }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                        Text(c.collection.name)
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("New collection…") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (newName.isNotBlank()) vm.createCollection(newName, withBook = book.id)
                onDismiss()
            }) { Text(if (newName.isNotBlank()) "Create and add" else "Done") }
        },
    )
}
