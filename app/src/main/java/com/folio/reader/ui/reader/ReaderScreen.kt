package com.folio.reader.ui.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.folio.reader.ui.theme.LocalAccent
import com.folio.reader.ui.theme.accentOn
import com.folio.reader.ui.theme.contentOn
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.webkit.WebViewAssetLoader
import com.folio.reader.FolioApp
import com.folio.reader.data.ReaderTheme
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    onOpenGraph: (String) -> Unit,
    onOpenEncyclopedia: (String) -> Unit,
    onOpenSettings: () -> Unit,
    vm: ReaderViewModel = viewModel(factory = ReaderViewModel.Factory),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ask by vm.ask.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val define by vm.define.collectAsStateWithLifecycle()
    val readAloud by vm.readAloud.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val theme = ui.settings.theme
    val paper = Color(theme.background)
    val ink = Color(theme.foreground)
    // The accent picked in Settings, adjusted to stay readable on this reading theme's page.
    val accent = accentOn(paper, LocalAccent.current)

    ImmersiveMode(showBars = ui.chromeVisible || ui.sheet != ReaderSheet.NONE, lightBars = !theme.isDark)
    TrackVisibility(vm::setVisible)

    val volumeKeys by vm.volumeKeys.collectAsStateWithLifecycle(false)
    // Not while a sheet is open or the voice is reading: then the buttons control the volume.
    VolumeKeyPaging(
        enabled = volumeKeys && ui.sheet == ReaderSheet.NONE && define == null && ui.editingHighlight == null && !readAloud.active,
    ) { forward -> if (forward) vm.nextPage() else vm.previousPage() }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is ReaderEvent.Message -> snackbar.showSnackbar(e.text)
                is ReaderEvent.OpenExternal -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, e.uri)) }
                is ReaderEvent.Copy -> {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Lumen", e.text))
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(paper)) {
        ReaderWebViewHost(ui, vm, Modifier.fillMaxSize())

        // Quiet running header/footer while reading.
        AnimatedVisibility(!ui.chromeVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Text(
                ui.chapterTitle,
                style = MaterialTheme.typography.labelMedium,
                color = ink.copy(alpha = .45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.statusBarsPadding().padding(top = 14.dp, start = 40.dp, end = 40.dp),
            )
        }
        AnimatedVisibility(!ui.chromeVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Text(
                    "${ui.page + 1} of ${ui.pageCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = ink.copy(alpha = .45f),
                    modifier = Modifier.weight(1f),
                )
                Text("${(ui.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = .45f))
            }
        }

        AnimatedVisibility(
            ui.chromeVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopChrome(
                title = ui.book?.title.orEmpty(),
                subtitle = ui.chapterTitle,
                paper = paper,
                ink = ink,
                bookmarked = ui.isBookmarked,
                onBack = onBack,
                onBookmark = vm::toggleBookmark,
                onToc = { vm.openSheet(ReaderSheet.TOC) },
                onAnnotations = { vm.openSheet(ReaderSheet.ANNOTATIONS) },
                onSearch = { vm.openSheet(ReaderSheet.SEARCH) },
                onGraph = { scope.launch { vm.snapshotForGraph(); onOpenGraph(vm.bookId) } },
                onEncyclopedia = { scope.launch { vm.snapshotForGraph(); onOpenEncyclopedia(vm.bookId) } },
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
                label = "${ui.chapterTitle} · p. ${ui.page + 1} of ${ui.pageCount}" + timeLeftLabel(ui.minutesLeft),
                paper = paper,
                ink = ink,
                accent = accent,
                onSeek = vm::seek,
                onSettings = { vm.openSheet(ReaderSheet.SETTINGS) },
                onRecap = vm::openChat,
                onListen = vm::startReadAloud,
                onPrevChapter = { vm.skipChapter(forward = false) },
                onNextChapter = { vm.skipChapter(forward = true) },
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
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
    }

    when (ui.sheet) {
        ReaderSheet.TOC -> TocSheet(ui, onDismiss = vm::closeSheet, onSelect = { spine, fragment ->
            vm.closeSheet()
            vm.jumpTo(spine, fragment?.let { NavTarget.Fragment(it) } ?: NavTarget.Offset(0))
        })
        ReaderSheet.ANNOTATIONS -> AnnotationsSheet(
            ui,
            onDismiss = vm::closeSheet,
            onOpen = vm::goToAnnotation,
            onDeleteBookmark = vm::deleteBookmark,
        )
        ReaderSheet.SETTINGS -> ReaderSettingsSheet(ui.settings, onDismiss = vm::closeSheet, onChange = vm::updateSettings)
        ReaderSheet.ASK -> AskSheet(
            state = ask,
            onDismiss = vm::closeSheet,
            onAsk = vm::askQuestion,
            onOpenSettings = { vm.closeSheet(); onOpenSettings() },
            onSaveGeminiKey = vm::saveGeminiKey,
            onReadWholeBook = vm::askWithWholeBook,
            onAskAgain = vm::askAgain,
        )
        ReaderSheet.SEARCH -> SearchSheet(search, vm::closeSheet, vm::setSearchQuery, vm::setSearchOnlyUpToHere, vm::openSearchHit)
        ReaderSheet.NONE -> Unit
    }

    define?.let { d ->
        DefineSheet(
            state = d,
            onDismiss = vm::closeDefine,
            onExplainWithAi = vm::explainWithAi,
            onAskMore = vm::askAboutDefined,
            onCopy = { text ->
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Lumen", text))
            },
            onSaveGeminiKey = vm::saveGeminiKeyAndRetryDefine,
            onOpenSettings = { vm.closeDefine(); onOpenSettings() },
        )
    }

    ui.editingHighlight?.let { h ->
        HighlightEditorSheet(
            highlight = h,
            focusNote = ui.editingNote,
            onDismiss = vm::dismissHighlightEditor,
            onSave = vm::updateHighlight,
            onDelete = vm::deleteHighlight,
            onAsk = vm::askAboutHighlight,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ReaderWebViewHost(ui: ReaderUiState, vm: ReaderViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val webView = remember {
        val app = context.applicationContext as FolioApp
        val loader = WebViewAssetLoader.Builder()
            .setDomain(READER_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/book/", BookPathHandler(app.container.books.booksRoot))
            .build()
        FolioWebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.textZoom = 100
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            webViewClient = ReaderWebViewClient(loader) { uri -> vm.onBridgeEvent(BridgeEvent.Link(uri.toString())) }
            addJavascriptInterface(ReaderBridge(vm::onBridgeEvent), "FolioBridge")
            onSelectionAction = { action, info -> vm.onSelection(action, info) }
        }
    }

    DisposableEffect(webView) { onDispose { webView.destroy() } }

    LaunchedEffect(webView) {
        vm.js.collect { call ->
            webView.evaluateJavascript(call.script) { result -> call.result?.complete(result ?: "null") }
        }
    }

    LaunchedEffect(ui.chapterUrl, ui.loadToken) {
        val url = ui.chapterUrl ?: return@LaunchedEffect
        vm.onChapterLoadStarted()
        webView.loadUrl(url)
    }

    LaunchedEffect(ui.settings.theme) { webView.setBackgroundColor(Color(ui.settings.theme.background).toArgb()) }

    AndroidView(factory = { webView }, modifier = modifier)
}

@Composable
internal fun TopChrome(
    title: String,
    subtitle: String,
    paper: Color,
    ink: Color,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onBookmark: () -> Unit,
    onToc: () -> Unit,
    onAnnotations: () -> Unit,
    onGraph: (() -> Unit)?,
    onEncyclopedia: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
) {
    data class Action(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val onClick: () -> Unit)
    // Secondary actions: shown as icons when there's room (tablets), otherwise in the "⋮" menu.
    val secondary = listOfNotNull(
        onGraph?.let { Action(Icons.Rounded.Hub, "Characters", it) },
        onEncyclopedia?.let { Action(Icons.AutoMirrored.Rounded.MenuBook, "Encyclopedia", it) },
        Action(Icons.Rounded.EditNote, "Highlights and bookmarks", onAnnotations),
        Action(Icons.AutoMirrored.Rounded.FormatListBulleted, "Contents", onToc),
    )
    var menu by remember { mutableStateOf(false) }
    Surface(color = paper, contentColor = ink, shadowElevation = 6.dp, shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().statusBarsPadding()) {
            val wide = maxWidth >= 560.dp
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = .6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (onSearch != null) IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, "Search in book") }
                if (wide) secondary.forEach { a -> IconButton(onClick = a.onClick) { Icon(a.icon, a.label) } }
                IconButton(onClick = onBookmark) {
                    Icon(
                        if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        "Bookmark",
                        tint = if (bookmarked) accentOn(paper, LocalAccent.current) else ink,
                    )
                }
                if (!wide) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            secondary.forEach { a ->
                                DropdownMenuItem(
                                    text = { Text(a.label) },
                                    leadingIcon = { Icon(a.icon, null) },
                                    onClick = { menu = false; a.onClick() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BottomChrome(
    progress: Float,
    label: String,
    paper: Color,
    ink: Color,
    accent: Color,
    onSeek: (Float) -> Unit,
    onSettings: (() -> Unit)?,
    onRecap: (() -> Unit)?,
    onListen: (() -> Unit)? = null,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(progress) }
    Surface(color = paper, contentColor = ink, shadowElevation = 10.dp, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = .65f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    "${((if (dragging) dragValue else progress) * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevChapter) { Icon(Icons.Rounded.SkipPrevious, "Previous chapter") }
                Slider(
                    value = if (dragging) dragValue else progress,
                    onValueChange = { dragging = true; dragValue = it },
                    onValueChangeFinished = { dragging = false; onSeek(dragValue) },
                    colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = ink.copy(alpha = .15f)),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onNextChapter) { Icon(Icons.Rounded.SkipNext, "Next chapter") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onSettings != null) {
                    Surface(onClick = onSettings, shape = CircleShape, color = ink.copy(alpha = .07f), contentColor = ink) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.TextFields, null, Modifier.size(18.dp))
                            Text("  Aa", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                if (onListen != null) {
                    Surface(onClick = onListen, shape = CircleShape, color = ink.copy(alpha = .07f), contentColor = ink) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Headphones, null, Modifier.size(18.dp))
                            Text("  Listen", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (onRecap != null) {
                    Button(
                        onClick = onRecap,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = contentOn(accent)),
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Where was I?")
                    }
                }
            }
        }
    }
}

/** After a jump: go back to where the reader was, or stay (which saves this page as the new position). */
@Composable
internal fun ReturnPill(
    progress: Float,
    paper: Color,
    ink: Color,
    accent: Color,
    onReturn: () -> Unit,
    onStay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = paper, contentColor = ink, shape = CircleShape, shadowElevation = 10.dp, border = BorderStroke(1.dp, ink.copy(alpha = .12f)), modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(onClick = onReturn, shape = CircleShape, color = Color.Transparent, contentColor = accent) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Back to where you were · ${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                }
            }
            IconButton(onClick = onStay) { Icon(Icons.Rounded.Close, "Stay here", Modifier.size(18.dp)) }
        }
    }
}

/** Floating read-aloud controls. Keeps the screen on while speaking. */
@Composable
internal fun ReadAloudBar(
    state: ReadAloudState,
    paper: Color,
    ink: Color,
    accent: Color,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onRate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    DisposableEffect(state.playing) {
        view.keepScreenOn = state.playing
        onDispose { view.keepScreenOn = false }
    }
    // The border keeps the bar visible on black pages, where the shadow doesn't show.
    Surface(color = paper, contentColor = ink, shape = CircleShape, shadowElevation = 10.dp, border = BorderStroke(1.dp, ink.copy(alpha = .12f)), modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Headphones, null, Modifier.padding(start = 8.dp).size(18.dp), tint = accent)
                IconButton(onClick = if (state.playing) onPause else onResume) {
                    Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Resume")
                }
                Surface(onClick = onRate, shape = CircleShape, color = ink.copy(alpha = .07f)) {
                    val r = state.rate
                    Text(
                        (if (r % 1f == 0f) "${r.toInt()}" else "$r") + "×",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                IconButton(onClick = onStop) { Icon(Icons.Rounded.Close, "Stop reading aloud") }
            }
            state.error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
            }
        }
    }
}

/** Tells the reading-time tracker whether the reader is actually on screen (resumed). */
@Composable
internal fun TrackVisibility(onVisible: (Boolean) -> Unit) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> onVisible(true)
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> onVisible(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            onVisible(false)
        }
    }
}

internal fun timeLeftLabel(minutes: Long?): String =
    if (minutes == null || minutes <= 0) "" else " · ~" + com.folio.reader.data.StatsRepository.formatDuration(minutes) + " left"

@Composable
internal fun ImmersiveMode(showBars: Boolean, lightBars: Boolean) {
    val view = LocalView.current
    val appDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val activity = remember(view) { view.context.findActivity() } ?: return
    DisposableEffect(showBars, lightBars) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightStatusBars = lightBars
        controller.isAppearanceLightNavigationBars = lightBars
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (showBars) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose {
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.show(WindowInsetsCompat.Type.systemBars())
            // Back to the app theme (which may differ from the system's light/dark setting).
            controller.isAppearanceLightStatusBars = !appDark
            controller.isAppearanceLightNavigationBars = !appDark
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
