package com.opennovel.reader.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opennovel.reader.data.AppSection
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import com.opennovel.reader.ui.screens.BrowseHostScreen
import com.opennovel.reader.ui.screens.DownloadsScreen
import com.opennovel.reader.ui.screens.ExtensionInfoScreen
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
import com.opennovel.reader.ui.screens.SettingsSectionScreen
import com.opennovel.reader.ui.screens.StatsScreen
import com.opennovel.reader.ui.screens.UpcomingScreen
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

/** Route hosting all five tabs; detail screens push on top of it. */
private const val HOME = "home"

@Composable
fun RootNav(factory: ViewModelProvider.Factory) {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = HOME) {
        composable(HOME) { HomeTabs(factory = factory, nav = nav) }
        detailRoutes(factory, nav)
    }
}

/**
 * The five top-level tabs in a pager, so they can be swiped between as well as
 * tapped — the bottom bar alone gave no way to move sideways, which is the
 * gesture people reach for first on a tabbed reader.
 *
 * The tabs live in one pager rather than as separate nav destinations because a
 * NavHost swaps destinations outright: there is no adjacent page to drag in, so
 * no amount of gesture handling on top of it would produce a real swipe.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeTabs(factory: ViewModelProvider.Factory, nav: NavHostController) {
    val pager = rememberPagerState(pageCount = { bottomDests.size })
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { SectionSwitcher(factory) },
        bottomBar = {
            NavigationBar {
                bottomDests.forEachIndexed { index, dest ->
                    NavigationBarItem(
                        selected = pager.currentPage == index,
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
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
        },
    ) { padding ->
        HorizontalPager(
            state = pager,
            modifier = Modifier.padding(padding),
            // Neighbours stay alive so swiping back to a tab keeps its scroll
            // position and doesn't re-run its loaders.
            beyondViewportPageCount = 1,
            key = { bottomDests[it].route },
        ) { page ->
            when (bottomDests[page]) {
                Dest.Library -> LibraryScreen(
                    factory = factory,
                    onOpenNovel = { novelId -> nav.navigate("novel/$novelId") },
                    // Ids are passed in the route so the screen can be reached
                    // for one title or a whole batch identically.
                    onMigrate = { ids -> nav.navigate("migrate/${ids.joinToString(",")}") },
                )

                Dest.Updates -> UpdatesScreen(
                    factory = factory,
                    onOpenChapter = { chapterId -> nav.navigate("reader/$chapterId") },
                    onOpenUpcoming = { nav.navigate("upcoming") },
                )

                Dest.History -> HistoryScreen(
                    factory = factory,
                    onOpenChapter = { chapterId -> nav.navigate("reader/$chapterId") },
                )

                Dest.Browse -> BrowseHostScreen(
                    factory = factory,
                    onOpenNovel = { novelId -> nav.navigate("novel/$novelId") },
                    onOpenRepos = { nav.navigate("extension_repos") },
                    onBrowseSource = { sourceId -> nav.navigate("source/$sourceId") },
                    onMigrateFromSource = { sourceId -> nav.navigate("migrate_source/$sourceId") },
                    onGlobalSearch = { nav.navigate("global_search") },
                )

                Dest.More -> MoreScreen(
                    factory = factory,
                    onOpenExtensions = { nav.navigate("extensions") },
                    onOpenDownloads = { nav.navigate("downloads") },
                    onOpenSettings = { nav.navigate("settings") },
                    onOpenSettingsSection = { section -> nav.navigate("settings/$section") },
                    onOpenStats = { nav.navigate("stats") },
                )
            }
        }
    }
}

/**
 * Switches the whole shell between comics and prose.
 *
 * It sits above the pager rather than becoming a sixth bottom tab because it is
 * not a destination: it re-scopes the five tabs that already exist. Hosting it in
 * the shell also means it stays put — and keeps its state — while pages swap.
 */
@Composable
private fun SectionSwitcher(factory: ViewModelProvider.Factory) {
    val vm: SectionViewModel = viewModel(factory = factory)
    val active by vm.active.collectAsStateWithLifecycle()

    Surface(tonalElevation = 2.dp) {
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            AppSection.entries.forEachIndexed { index, section ->
                SegmentedButton(
                    selected = active == section,
                    onClick = { vm.select(section) },
                    shape = SegmentedButtonDefaults.itemShape(index, AppSection.entries.size),
                ) { Text(section.label) }
            }
        }
    }
}

/** Everything reached from a tab, sharing one back stack. */
private fun NavGraphBuilder.detailRoutes(
    factory: ViewModelProvider.Factory,
    nav: NavHostController,
) {
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
    composable("extensions") {
        ExtensionsScreen(
            factory = factory,
            onOpenRepos = { nav.navigate("extension_repos") },
            onBrowseSource = { sourceId -> nav.navigate("source/$sourceId") },
            onOpenExtensionInfo = { pkg -> nav.navigate("extension_info/$pkg") },
        )
    }
    composable("extension_info/{pkg}") { entry ->
        val pkg = entry.arguments?.getString("pkg") ?: return@composable
        ExtensionInfoScreen(
            packageName = pkg,
            factory = factory,
            onBack = { nav.popBackStack() },
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
    composable("stats") {
        StatsScreen(factory = factory, onBack = { nav.popBackStack() })
    }
    composable("upcoming") {
        UpcomingScreen(
            factory = factory,
            onBack = { nav.popBackStack() },
            onOpenNovel = { novelId -> nav.navigate("novel/$novelId") },
        )
    }
    composable("settings") {
        SettingsScreen(
            factory = factory,
            onBack = { nav.popBackStack() },
            onOpenSection = { section -> nav.navigate("settings/$section") },
        )
    }
    composable("settings/{section}") { entry ->
        val section = entry.arguments?.getString("section") ?: return@composable
        SettingsSectionScreen(
            sectionId = section,
            factory = factory,
            onBack = { nav.popBackStack() },
            onOpenRepos = { nav.navigate("extension_repos") },
        )
    }
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

