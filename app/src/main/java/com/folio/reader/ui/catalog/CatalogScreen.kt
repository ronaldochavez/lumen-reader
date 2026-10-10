package com.folio.reader.ui.catalog

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.folio.reader.data.catalog.CatalogBook
import com.folio.reader.data.catalog.CatalogHttp
import com.folio.reader.data.catalog.CatalogSource
import com.folio.reader.ui.theme.Folio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.absoluteValue

private const val COMPACT_ROWS = 6

/** Search Project Gutenberg and Standard Ebooks, and add public-domain books to the library in one tap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
    vm: CatalogViewModel = viewModel(factory = CatalogViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val focus = LocalFocusManager.current
    var open by remember { mutableStateOf<CatalogBook?>(null) }

    val list = rememberLazyListState()

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // New results start at the top.
    LaunchedEffect(state.searched, state.source, state.language) { list.scrollToItem(0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Free books") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            state = list,
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::setQuery,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    placeholder = { Text("Title or author") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) IconButton(onClick = vm::clearSearch) { Icon(Icons.Rounded.Close, "Clear") }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.search() }),
                )
                Filters(state, vm::setSource, vm::setLanguage)
            }

            val sources = state.visibleSources
            // With several sources on screen each shows a few books, so all of them are visible without scrolling far.
            val compact = sources.size > 1
            val allEmpty = sources.all { state.results[it]?.let { r -> !r.loading && r.error == null && r.books.isEmpty() } == true }
            if (allEmpty) {
                item { EmptyResults(state.searched) }
            }
            sources.forEach { source ->
                val r = state.results[source] ?: SourceResults(loading = true)
                // A source with nothing for this search is left out, unless it's the only one shown.
                if (r.books.isEmpty() && !r.loading && r.error == null && (sources.size > 1 || allEmpty)) return@forEach
                item(key = "header-$source") { SectionHeader(source, state.searched.isBlank()) }
                items(if (compact) r.books.take(COMPACT_ROWS) else r.books, key = { it.key }) { book ->
                    BookRow(
                        book = book,
                        download = state.downloads[book.key],
                        libraryId = state.libraryIdFor(book),
                        onClick = { open = book; vm.loadDetails(book) },
                        onDownload = { vm.download(book) },
                        onOpen = onOpenBook,
                    )
                }
                item(key = "footer-$source") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        when {
                            r.loading -> SlowLoading(source)
                            r.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "Couldn't reach ${source.label}: ${r.error}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 24.dp),
                                )
                                TextButton(onClick = { vm.retry(source) }) { Text("Try again") }
                            }
                            compact && (r.books.size > COMPACT_ROWS || r.hasMore) ->
                                OutlinedButton(onClick = { vm.setSource(source) }) { Text("See all from ${source.label}") }
                            r.hasMore -> OutlinedButton(onClick = { vm.loadMore(source) }) { Text("More from ${source.label}") }
                        }
                    }
                }
            }
            item {
                Text(
                    "These books are in the public domain in the United States. If you live elsewhere, check your " +
                        "country's copyright rules before downloading.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp).navigationBarsPadding(),
                )
            }
        }
    }

    open?.let { book ->
        BookSheet(
            book = book,
            state = state,
            onDismiss = { open = null },
            onDownload = { vm.download(book) },
            onOpen = { open = null; onOpenBook(it) },
        )
    }
}

@Composable
private fun Filters(state: CatalogUiState, onSource: (CatalogSource?) -> Unit, onLanguage: (CatalogLanguage) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = state.source == null, onClick = { onSource(null) }, label = { Text("All") })
            CatalogSource.entries.forEach { s ->
                FilterChip(selected = state.source == s, onClick = { onSource(s) }, label = { Text(s.label) })
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CatalogLanguage.entries.forEach { l ->
                FilterChip(selected = state.language == l, onClick = { onLanguage(l) }, label = { Text(l.label) })
            }
        }
    }
}

@Composable
private fun SectionHeader(source: CatalogSource, browsing: Boolean) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp)) {
        Text(
            when {
                !browsing -> source.label
                source == CatalogSource.STANDARD_EBOOKS -> "New from Standard Ebooks"
                else -> "Popular on Project Gutenberg"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            when (source) {
                CatalogSource.STANDARD_EBOOKS -> "Carefully proofread and formatted editions of classics, by volunteers"
                CatalogSource.GUTENBERG -> "The largest free library: over 75,000 public-domain books"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A spinner; if the catalog takes a while (Gutendex often does when busy), a line saying it's still working. */
@Composable
private fun SlowLoading(source: CatalogSource) {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(8_000)
        slow = true
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        if (slow) {
            Text(
                "${source.label}'s search can be slow. Still looking…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun EmptyResults(query: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (query.isBlank()) "Nothing here" else "No free books match “$query”", style = MaterialTheme.typography.titleMedium)
        Text(
            "Only books old enough to be in the public domain are here: try an author or a classic, or another language.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun BookRow(
    book: CatalogBook,
    download: DownloadState?,
    libraryId: String?,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteCover(book, Modifier.width(52.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                book.author,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                metaLine(book),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        DownloadButton(download, libraryId, onDownload, onOpen)
    }
}

@Composable
private fun DownloadButton(download: DownloadState?, libraryId: String?, onDownload: () -> Unit, onOpen: (String) -> Unit) {
    Box(Modifier.size(width = 72.dp, height = 48.dp), contentAlignment = Alignment.Center) {
        when {
            libraryId != null -> TextButton(onClick = { onOpen(libraryId) }) { Text("Read") }
            download is DownloadState.Running ->
                if (download.progress != null) CircularProgressIndicator(progress = { download.progress }, Modifier.size(28.dp), strokeWidth = 3.dp)
                else CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            download is DownloadState.Failed ->
                IconButton(onClick = onDownload) { Icon(Icons.Rounded.Refresh, "Try again", tint = MaterialTheme.colorScheme.error) }
            else -> IconButton(onClick = onDownload) { Icon(Icons.Rounded.Download, "Download", tint = MaterialTheme.colorScheme.primary) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookSheet(
    book: CatalogBook,
    state: CatalogUiState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
    val download = state.downloads[book.key]
    val libraryId = state.libraryIdFor(book)
    val details = state.details[book.key]
    fun browse(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Row {
                RemoteCover(book, Modifier.width(96.dp))
                Column(Modifier.weight(1f).padding(start = 18.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleLarge)
                    Text(book.author, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        listOfNotNull(book.source.label, metaLine(book).ifBlank { null }, details?.wordCount?.let(::readingTime))
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            val about = details?.summary ?: book.subjects.takeIf { it.isNotEmpty() }?.joinToString(" · ")
            if (about != null) {
                Text(
                    about,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = if (details?.summary != null) FontStyle.Italic else FontStyle.Normal,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            when {
                libraryId != null -> Button(onClick = { onOpen(libraryId) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Rounded.MenuBook, null)
                    Text("  Read now")
                }
                download is DownloadState.Running -> Column {
                    Text("Downloading…", style = MaterialTheme.typography.labelLarge)
                    if (download.progress != null) LinearProgressIndicator(progress = { download.progress }, Modifier.fillMaxWidth().padding(top = 8.dp))
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                else -> Button(onClick = onDownload, Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Download, null)
                    Text(if (download is DownloadState.Failed) "  Try again" else "  Add to library — free")
                }
            }
            if (download is DownloadState.Failed) {
                Text(download.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text(
                when (book.source) {
                    CatalogSource.STANDARD_EBOOKS ->
                        "Standard Ebooks is a volunteer-run nonprofit. Its editions are free; donations keep it going."
                    CatalogSource.GUTENBERG ->
                        "Project Gutenberg is a volunteer-run library of public-domain books, free since 1971."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.padding(top = 4.dp)) {
                TextButton(onClick = { browse(book.pageUrl) }) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                    Text("  View on website")
                }
                if (book.source == CatalogSource.STANDARD_EBOOKS) {
                    FilledTonalButton(onClick = { browse("https://standardebooks.org/donate") }) { Text("Support them") }
                } else {
                    FilledTonalButton(onClick = { browse("https://www.gutenberg.org/donate/") }) { Text("Support them") }
                }
            }
        }
    }
}

private fun metaLine(book: CatalogBook): String = listOfNotNull(
    book.language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).ifBlank { it } },
    book.downloads?.takeIf { it > 0 }?.let { n ->
        when {
            n >= 1000 -> "${n / 1000}K downloads"
            else -> "$n downloads"
        }
    },
).joinToString(" · ")

private fun readingTime(words: Int): String {
    val hours = words / 250 / 60
    return if (hours >= 1) "about $hours h of reading" else "under an hour of reading"
}

/** Small covers for result lists: downscaled on decode and kept in memory while the app runs. */
private val coverCache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

@Composable
private fun RemoteCover(book: CatalogBook, modifier: Modifier) {
    val url = book.coverUrl
    val bitmap by produceState(url?.let { coverCache.get(it) }, url) {
        if (value != null || url == null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = CatalogHttp.getBytes(url)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 240) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            }.getOrNull()
        }?.also { coverCache.put(url, it) }
    }
    val (a, b) = Folio.coverPalettes[book.title.hashCode().absoluteValue % Folio.coverPalettes.size]
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.linearGradient(listOf(a, b))),
    ) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
