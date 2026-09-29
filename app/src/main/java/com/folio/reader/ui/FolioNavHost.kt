package com.folio.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.folio.reader.FolioApp
import com.folio.reader.data.db.BookFormat
import com.folio.reader.ui.reader.fixed.FixedReaderScreen
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.folio.reader.ui.collections.CollectionDetailScreen
import com.folio.reader.ui.collections.CollectionsScreen
import com.folio.reader.ui.encyclopedia.EncyclopediaScreen
import com.folio.reader.ui.graph.CharacterGraphScreen
import com.folio.reader.ui.library.LibraryScreen
import com.folio.reader.ui.reader.ReaderScreen
import com.folio.reader.ui.settings.SettingsScreen
import com.folio.reader.ui.stats.StatsScreen

private object Routes {
    const val LIBRARY = "library"
    const val COLLECTIONS = "collections"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val COLLECTION = "collection/{id}"
    const val READER = "reader/{bookId}"
    const val GRAPH = "graph/{bookId}"
    const val ENCYCLOPEDIA = "encyclopedia/{bookId}"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.LIBRARY, "Biblioteca", Icons.AutoMirrored.Rounded.LibraryBooks),
    Tab(Routes.COLLECTIONS, "Colecciones", Icons.Rounded.CollectionsBookmark),
    Tab(Routes.STATS, "Progreso", Icons.Rounded.Insights),
    Tab(Routes.SETTINGS, "Ajustes", Icons.Rounded.Settings),
)

@Composable
fun FolioNavHost(nav: NavHostController = rememberNavController()) {
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = tabs.any { it.route == route }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.switchTab(tab.route) },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = Routes.LIBRARY,
            modifier = Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner),
        ) {
            composable(Routes.LIBRARY) {
                LibraryScreen(onOpenBook = { nav.navigate("reader/$it") }, onOpenStats = { nav.switchTab(Routes.STATS) })
            }
            composable(Routes.COLLECTIONS) {
                CollectionsScreen(onOpenCollection = { nav.navigate("collection/$it") })
            }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(Routes.STATS) { StatsScreen(onOpenBook = { nav.navigate("reader/$it") }) }
            composable(Routes.COLLECTION, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                CollectionDetailScreen(
                    collectionId = entry.arguments?.getLong("id") ?: 0L,
                    onBack = { nav.popBackStack() },
                    onOpenBook = { nav.navigate("reader/$it") },
                )
            }
            composable(Routes.READER, arguments = listOf(navArgument("bookId") { type = NavType.StringType })) { entry ->
                val bookId = entry.arguments?.getString("bookId").orEmpty()
                val app = LocalContext.current.applicationContext as FolioApp
                // Reflowable books (EPUB and converted formats) use the WebView reader; PDFs and comics the page reader.
                val format by produceState<BookFormat?>(null, bookId) {
                    value = BookFormat.of(app.container.books.get(bookId)?.format)
                }
                when (format) {
                    null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                    BookFormat.REFLOW -> ReaderScreen(
                        onBack = { nav.popBackStack() },
                        onOpenGraph = { nav.navigate("graph/$it") },
                        onOpenEncyclopedia = { nav.navigate("encyclopedia/$it") },
                        onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                    else -> FixedReaderScreen(
                        onBack = { nav.popBackStack() },
                        onOpenGraph = { nav.navigate("graph/$it") },
                        onOpenEncyclopedia = { nav.navigate("encyclopedia/$it") },
                        onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                }
            }
            composable(Routes.ENCYCLOPEDIA, arguments = listOf(navArgument("bookId") { type = NavType.StringType })) {
                EncyclopediaScreen(onBack = { nav.popBackStack() }, onOpenSettings = { nav.navigate(Routes.SETTINGS) })
            }
            composable(Routes.GRAPH, arguments = listOf(navArgument("bookId") { type = NavType.StringType })) {
                CharacterGraphScreen(
                    onBack = { nav.popBackStack() },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }
        }
    }
}

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
