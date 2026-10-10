package com.folio.reader.ui.reader.fixed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.folio.reader.ui.theme.LocalAccent
import com.folio.reader.ui.theme.accentOn
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.folio.reader.FolioApp
import com.folio.reader.data.ReaderTheme
import com.folio.reader.data.convert.PdfImport
import com.folio.reader.data.db.BookFormat
import com.folio.reader.ui.reader.AnnotationsSheet
import com.folio.reader.ui.reader.AskSheet
import com.folio.reader.ui.reader.BottomChrome
import com.folio.reader.ui.reader.ImmersiveMode
import com.folio.reader.ui.reader.ReaderSheet
import com.folio.reader.ui.reader.TocSheet
import com.folio.reader.ui.reader.SearchSheet
import com.folio.reader.ui.reader.TopChrome
import com.folio.reader.ui.reader.ReadAloudBar
import com.folio.reader.ui.reader.ReturnPill
import com.folio.reader.ui.reader.VolumeKeyPaging
import com.folio.reader.ui.reader.TrackVisibility
import com.folio.reader.ui.reader.timeLeftLabel
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun FixedReaderScreen(
    onBack: () -> Unit,
    onOpenGraph: (String) -> Unit,
    onOpenEncyclopedia: (String) -> Unit,
    onOpenSettings: () -> Unit,
    vm: FixedReaderViewModel = viewModel(factory = FixedReaderViewModel.Factory),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ask by vm.ask.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val readAloud by vm.readAloud.collectAsStateWithLifecycle()
    val format by vm.format.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val theme = ui.settings.theme
    val paper = Color(theme.background)
    val ink = Color(theme.foreground)
    // The accent picked in Settings, adjusted to stay readable on this reading theme's page.
    val accent = accentOn(paper, LocalAccent.current)
    val hasText = format == BookFormat.PDF
    val app = LocalContext.current.applicationContext as FolioApp

    ImmersiveMode(showBars = ui.chromeVisible || ui.sheet != ReaderSheet.NONE, lightBars = !theme.isDark)
    TrackVisibility(vm::setVisible)

    val book = ui.book
    Box(Modifier.fillMaxSize().background(paper)) {
        if (book == null || ui.chapters.isEmpty()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
            return@Box
        }
        val source = remember(book.id, format) {
            val bookDir = app.container.books.bookDir(book.id)
            if (format == BookFormat.PDF) runCatching { PdfPageSource(File(bookDir, PdfImport.FILE_NAME)) }.getOrNull()
            else ImagePageSource(bookDir, ui.chapters.map { it.href })
        }
        DisposableEffect(source) { onDispose { source?.close() } }
        if (source == null) {
            Text("Couldn't open the file.", Modifier.align(Alignment.Center), color = ink)
            return@Box
        }

        key(book.id) {
            val pager = rememberPagerState(initialPage = ui.spineIndex) { ui.chapters.size }
            var zoomed by remember { mutableStateOf(false) }

            LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { vm.onPageSettled(it) } }
            LaunchedEffect(pager) { vm.pageRequests.collect { pager.scrollToPage(it) } }

            val volumeKeys by vm.volumeKeys.collectAsStateWithLifecycle(false)
            VolumeKeyPaging(enabled = volumeKeys && !zoomed && ui.sheet == ReaderSheet.NONE && !readAloud.active) { forward ->
                val target = (pager.currentPage + if (forward) 1 else -1).coerceIn(0, ui.chapters.size - 1)
                scope.launch { pager.animateScrollToPage(target) }
            }

            HorizontalPager(
                state = pager,
                userScrollEnabled = !zoomed,
                beyondViewportPageCount = 1,
                key = { it },
                modifier = Modifier.fillMaxSize(),
            ) { index ->
                ZoomablePage(
                    source = source,
                    index = index,
                    onZoomChange = { zoomed = it },
                    onTap = { x, width ->
                        when {
                            x < width * 0.28f -> scope.launch { pager.animateScrollToPage((index - 1).coerceAtLeast(0)) }
                            x > width * 0.72f -> scope.launch { pager.animateScrollToPage((index + 1).coerceAtMost(ui.chapters.size - 1)) }
                            else -> vm.toggleChrome()
                        }
                    },
                )
            }
        }

        AnimatedVisibility(!ui.chromeVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 10.dp)) {
                Text(
                    "${ui.spineIndex + 1} of ${ui.chapters.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = ink.copy(alpha = .5f),
                    modifier = Modifier.weight(1f),
                )
                Text("${(ui.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = .5f))
            }
        }

        AnimatedVisibility(
            ui.chromeVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopChrome(
                title = book.title,
                subtitle = ui.chapterTitle,
                paper = paper,
                ink = ink,
                bookmarked = ui.bookmarks.any { it.spineIndex == ui.spineIndex },
                onBack = onBack,
                onBookmark = vm::toggleBookmark,
                onToc = { vm.openSheet(ReaderSheet.TOC) },
                onAnnotations = { vm.openSheet(ReaderSheet.ANNOTATIONS) },
                onSearch = if (hasText) ({ vm.openSheet(ReaderSheet.SEARCH) }) else null,
                onGraph = if (hasText) ({ scope.launch { vm.snapshotForGraph(); onOpenGraph(vm.bookId) } }) else null,
                onEncyclopedia = if (hasText) ({ scope.launch { vm.snapshotForGraph(); onOpenEncyclopedia(vm.bookId) } }) else null,
            )
        }

        AnimatedVisibility(
            ui.chromeVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomChrome(
                progress = ui.progress,
                label = "${ui.chapterTitle} · ${ui.spineIndex + 1} of ${ui.chapters.size}" + timeLeftLabel(ui.minutesLeft),
                paper = paper,
                ink = ink,
                accent = accent,
                onSeek = vm::seek,
                onSettings = null,
                onRecap = if (hasText) vm::openChat else null,
                onListen = if (hasText) vm::startReadAloud else null,
                onPrevChapter = { vm.goToPage(ui.spineIndex - 1) },
                onNextChapter = { vm.goToPage(ui.spineIndex + 1) },
            )
        }
        if (readAloud.active) {
            ReadAloudBar(
                state = readAloud,
                paper = paper,
                ink = ink,
                accent = accent,
                onPause = vm::pauseReadAloud,
                onResume = vm::resumeReadAloud,
                onStop = vm::stopReadAloud,
                onRate = vm::cycleReadAloudRate,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = if (ui.chromeVisible) 190.dp else 44.dp),
            )
        }
        ui.returnPoint?.let { p ->
            ReturnPill(
                progress = p.progress,
                paper = paper,
                ink = ink,
                accent = accent,
                onReturn = vm::returnToPoint,
                onStay = vm::dismissReturnPoint,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = (if (ui.chromeVisible) 190.dp else 44.dp) + (if (readAloud.active) 64.dp else 0.dp)),
            )
        }
    }

    when (ui.sheet) {
        ReaderSheet.TOC -> TocSheet(ui, onDismiss = vm::closeSheet, onSelect = { page, _ -> vm.jumpToPage(page) })
        ReaderSheet.ANNOTATIONS -> AnnotationsSheet(
            ui,
            onDismiss = vm::closeSheet,
            onOpen = { page, _, _ -> vm.jumpToPage(page) },
            onDeleteBookmark = vm::deleteBookmark,
        )
        ReaderSheet.ASK -> AskSheet(
            state = ask,
            onDismiss = vm::closeSheet,
            onAsk = vm::askQuestion,
            onOpenSettings = { vm.closeSheet(); onOpenSettings() },
            onSaveGeminiKey = vm::saveGeminiKey,
        )
        ReaderSheet.SEARCH -> SearchSheet(search, vm::closeSheet, vm::setSearchQuery, vm::setSearchOnlyUpToHere, vm::openSearchHit)
        else -> Unit
    }
}

/** One page image with pinch-to-zoom; single-finger drags pass through to the pager unless zoomed in. */
@Composable
private fun ZoomablePage(
    source: PageSource,
    index: Int,
    onZoomChange: (Boolean) -> Unit,
    onTap: (x: Float, width: Float) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val targetWidth = (constraints.maxWidth * 1.6f).toInt()
        val bitmap by produceState<ImageBitmap?>(null, index, targetWidth) {
            value = source.render(index, targetWidth)?.asImageBitmap()
        }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        fun clamp(o: Offset, s: Float): Offset {
            val maxX = widthPx * (s - 1f) / 2f
            val maxY = heightPx * (s - 1f) / 2f
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }

        val img = bitmap
        if (img == null) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        } else {
            Image(
                bitmap = img,
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(vertical = 8.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { p ->
                                if (scale > 1.01f) {
                                    scale = 1f; offset = Offset.Zero; onZoomChange(false)
                                } else {
                                    scale = 2.5f
                                    offset = clamp(Offset((size.width / 2f - p.x) * 1.5f, (size.height / 2f - p.y) * 1.5f), 2.5f)
                                    onZoomChange(true)
                                }
                            },
                            onTap = { p -> if (scale <= 1.01f) onTap(p.x, size.width.toFloat()) },
                        )
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.size > 1 || scale > 1.01f) {
                                    val newScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                                    offset = if (newScale <= 1.01f) Offset.Zero else clamp(offset + event.calculatePan() * newScale, newScale)
                                    scale = newScale
                                    onZoomChange(scale > 1.01f)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
            )
        }
    }
}
