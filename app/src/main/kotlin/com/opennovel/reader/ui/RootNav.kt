package com.opennovel.reader.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.opennovel.reader.ui.screens.BrowseHostScreen
import com.opennovel.reader.ui.screens.DownloadsScreen
import com.opennovel.reader.ui.screens.ExtensionReposScreen
import com.opennovel.reader.ui.screens.ExtensionsScreen
import com.opennovel.reader.ui.screens.GlobalSearchScreen
import com.opennovel.reader.ui.screens.SourceBrowseScreen
import com.opennovel.reader.ui.screens.HistoryScreen
import com.opennovel.reader.ui.screens.LibraryScreen
import com.opennovel.reader.ui.screens.MigrateSourceScreen
import com.opennovel.reader.ui.screens.MigrationScreen
import com.opennovel.reader.ui.screens.MoreScreen
import com.opennovel.reader.ui.screens.NovelDetailScreen
import com.opennovel.reader.ui.screens.ReaderScreen
import com.opennovel.reader.ui.screens.SettingsScreen
import com.opennovel.reader.ui.screens.UpdatesScreen

/**
 * Bottom-tab layout mirrors Mihon's: Library, Updates, History, Browse, More.
 * Five is the practical maximum before labels truncate, so the less-frequent
 * destinations (Extensions, Downloads, Settings) live behind More.
 */
private sealed class Dest(val route: String, val label: String) {
    data object Library : Dest("library", "Library")
    data object Updates : Dest("updates", "Updates")
    data object History : Dest("history", "History")
    data object Browse : Dest("browse", "Browse")
    data object More : Dest("more", "More")
}

private val bottomDests =
    listOf(Dest.Library, Dest.Updates, Dest.History, Dest.Browse, Dest.More)

@Composable
fun RootNav(factory: ViewModelProvider.Factory) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = currentRoute in bottomDests.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    val current = backStack?.destination
                    bottomDests.forEach { dest ->
                        NavigationBarItem(
                            selected = current?.hierarchy?.any { it.route == dest.route } == true,
                            onClick = {
                                nav.navigate(dest.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    when (dest) {
                                        Dest.Library -> Icons.AutoMirrored.Filled.MenuBook
                                        Dest.Updates -> Icons.Filled.NewReleases
                                        Dest.History -> Icons.Filled.History
                                        Dest.Browse -> Icons.Filled.Explore
                                        Dest.More -> Icons.Filled.MoreHoriz
                                    },
                                    contentDescription = dest.label,
                                )
                            },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Dest.Library.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Dest.Library.route) {
                LibraryScreen(
                    factory = factory,
                    onOpenNovel = { novelId -> nav.navigate("novel/$novelId") },
                    // Ids are passed in the route so the screen can be reached
                    // for one title or a whole batch identically.
                    onMigrate = { ids -> nav.navigate("migrate/${ids.joinToString(",")}") },
                )
            }
            composable("migrate/{novelIds}") { entry ->
                val ids = entry.arguments?.getString("novelIds")
                    ?.split(",")
                    ?.mapNotNull { it.toLongOrNull() }
                    .orEmpty()
                MigrationScreen(
                    novelIds = ids,
                    factory = factory,
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Dest.Updates.route) {
                UpdatesScreen(
                    factory = factory,
                    onOpenChapter = { chapterId -> nav.navigate("reader/$chapterId") },
                )
            }
            composable(Dest.History.route) {
                HistoryScreen(
                    factory = factory,
                    onOpenChapter = { chapterId -> nav.navigate("reader/$chapterId") },
                )
            }
            composable(Dest.Browse.route) {
                BrowseHostScreen(
                    factory = factory,
                    onOpenNovel = { novelId -> nav.navigate("novel/$novelId") },
                    onOpenRepos = { nav.navigate("extension_repos") },
                    onBrowseSource = { sourceId -> nav.navigate("source/$sourceId") },
                    onMigrateFromSource = { sourceId -> nav.navigate("migrate_source/$sourceId") },
                    onGlobalSearch = { nav.navigate("global_search") },
                )
            }
            composable("global_search") {
                GlobalSearchScreen(
                    factory = factory,
                    onOpenNovel = { novelId -> nav.navigate("novel/$novelId") },
                    onBack = { nav.popBackStack() },
                )
            }
            composable("migrate_source/{sourceId}") { entry ->
                val id = entry.arguments?.getString("sourceId")?.toLongOrNull() ?: return@composable
                MigrateSourceScreen(
                    sourceId = id,
                    factory = factory,
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Dest.More.route) {
                MoreScreen(
                    onOpenExtensions = { nav.navigate("extensions") },
                    onOpenDownloads = { nav.navigate("downloads") },
                    onOpenSettings = { nav.navigate("settings") },
                )
            }
            composable("extensions") {
                ExtensionsScreen(
                    factory = factory,
                    onOpenRepos = { nav.navigate("extension_repos") },
                    onBrowseSource = { sourceId -> nav.navigate("source/$sourceId") },
                )
            }
            composable("extension_repos") {
                ExtensionReposScreen(factory = factory, onBack = { nav.popBackStack() })
            }
            composable("source/{sourceId}") { entry ->
                val id = entry.arguments?.getString("sourceId")?.toLongOrNull() ?: return@composable
                SourceBrowseScreen(
                    sourceId = id,
                    factory = factory,
                    onOpenNovel = { novelId -> nav.navigate("novel/$novelId") },
                    onBack = { nav.popBackStack() },
                )
            }
            composable("downloads") {
                DownloadsScreen(
                    factory = factory,
                    onOpenChapter = { chapterId -> nav.navigate("reader/$chapterId") },
                )
            }
            composable("settings") { SettingsScreen(factory = factory) }
            composable("novel/{novelId}") { entry ->
                val id = entry.arguments?.getString("novelId")?.toLongOrNull() ?: return@composable
                NovelDetailScreen(
                    novelId = id,
                    factory = factory,
                    onOpenChapter = { chapterId -> nav.navigate("reader/$chapterId") },
                    onBack = { nav.popBackStack() },
                    onMigrate = { id2 -> nav.navigate("migrate/$id2") },
                )
            }
            composable("reader/{chapterId}") { entry ->
                val id = entry.arguments?.getString("chapterId")?.toLongOrNull() ?: return@composable
                ReaderScreen(chapterId = id, factory = factory, onBack = { nav.popBackStack() })
            }
        }
    }
}

