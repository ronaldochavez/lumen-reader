package com.folio.reader.ui.collections

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.reader.AppContainer
import com.folio.reader.container
import com.folio.reader.data.db.CollectionEntity
import com.folio.reader.data.db.CollectionWithBooks
import com.folio.reader.ui.components.BookCover
import com.folio.reader.ui.components.ProgressBar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CollectionsViewModel(private val c: AppContainer) : ViewModel() {
    val collections = c.db.collections().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun observe(id: Long) = c.db.collections().observe(id)

    fun create(name: String) = viewModelScope.launch {
        c.db.collections().insert(CollectionEntity(name = name.trim(), createdAt = System.currentTimeMillis()))
    }

    fun rename(id: Long, name: String) = viewModelScope.launch { c.db.collections().rename(id, name.trim()) }
    fun delete(id: Long) = viewModelScope.launch { c.db.collections().delete(id) }
    fun removeBook(collectionId: Long, bookId: String) = viewModelScope.launch { c.db.collections().removeBook(collectionId, bookId) }

    companion object {
        val Factory = viewModelFactory { initializer { CollectionsViewModel(container) } }
    }
}

@Composable
fun CollectionsScreen(onOpenCollection: (Long) -> Unit, vm: CollectionsViewModel = viewModel(factory = CollectionsViewModel.Factory)) {
    val collections by vm.collections.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Row(Modifier.statusBarsPadding().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("Organiza tus lecturas", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                        Text("Colecciones", style = MaterialTheme.typography.displaySmall)
                    }
                    FilledTonalButton(onClick = { creating = true }, shape = CircleShape) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Text(" Nueva")
                    }
                }
            }
            val list = collections
            if (list != null && list.isEmpty()) {
                item { EmptyCollections { creating = true } }
            }
            items(list.orEmpty(), key = { it.collection.id }) { c -> CollectionCard(c) { onOpenCollection(c.collection.id) } }
        }
    }

    if (creating) {
        NameDialog(title = "Nueva colección", initial = "", confirm = "Crear", onDismiss = { creating = false }) {
            vm.create(it); creating = false
        }
    }
}

@Composable
private fun CollectionCard(c: CollectionWithBooks, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // Fanned stack of up to three covers.
            Box(Modifier.width(110.dp).height(96.dp)) {
                c.books.take(3).reversed().forEachIndexed { i, book ->
                    val n = c.books.take(3).size
                    val pos = n - 1 - i
                    BookCover(
                        book,
                        Modifier
                            .width(62.dp)
                            .offset(x = (pos * 22).dp, y = (pos * 2).dp)
                            .rotate((pos - 1) * 5f),
                        corner = 8.dp,
                        elevation = 4.dp,
                    )
                }
                if (c.books.isEmpty()) {
                    Box(
                        Modifier.size(64.dp, 96.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.outlineVariant),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.CollectionsBookmark, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.collection.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (c.books.size == 1) "1 libro" else "${c.books.size} libros",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (c.books.isNotEmpty()) {
                    val avg = c.books.map { it.progress }.average().toFloat()
                    ProgressBar(avg, Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.primary, height = 4.dp)
                }
            }
        }
    }
}

@Composable
private fun EmptyCollections(onCreate: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Aún no tienes colecciones", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Agrupa libros como quieras: «Por leer», «Fantasía», «Club de lectura»…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(onClick = onCreate, modifier = Modifier.padding(top = 8.dp)) { Text("Crear la primera") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionDetailScreen(
    collectionId: Long,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
    vm: CollectionsViewModel = viewModel(factory = CollectionsViewModel.Factory),
) {
    val flow = remember(collectionId) { vm.observe(collectionId) }
    val data by flow.collectAsStateWithLifecycle(initialValue = null)
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(data?.collection?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás") } },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Más") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Renombrar") }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text("Eliminar colección") }, onClick = { menu = false; deleting = true })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        val books = data?.books.orEmpty()
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (data != null && books.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Vacía por ahora. En la biblioteca, mantén presionado un libro → «Agregar a colección».",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(books, key = { it.id }) { book ->
                var bookMenu by remember { mutableStateOf(false) }
                Column {
                    Box {
                        Surface(onClick = { onOpenBook(book.id) }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.background) {
                            BookCover(book, Modifier.fillMaxWidth())
                        }
                        IconButton(
                            onClick = { bookMenu = true },
                            modifier = Modifier.align(Alignment.TopEnd).size(36.dp),
                        ) {
                            Box(Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.background.copy(alpha = .85f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.MoreVert, "Opciones", Modifier.size(18.dp))
                            }
                        }
                        DropdownMenu(expanded = bookMenu, onDismissRequest = { bookMenu = false }) {
                            DropdownMenuItem(text = { Text("Quitar de la colección") }, onClick = {
                                bookMenu = false; vm.removeBook(collectionId, book.id)
                            })
                        }
                    }
                    Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                    Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }

    if (renaming) {
        NameDialog("Renombrar", data?.collection?.name.orEmpty(), "Guardar", { renaming = false }) {
            vm.rename(collectionId, it); renaming = false
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("¿Eliminar colección?") },
            text = { Text("Los libros seguirán en tu biblioteca.") },
            confirmButton = { TextButton(onClick = { vm.delete(collectionId); deleting = false; onBack() }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Nombre") },
                shape = MaterialTheme.shapes.medium,
            )
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
