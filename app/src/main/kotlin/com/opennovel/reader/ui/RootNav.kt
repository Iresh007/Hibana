package com.opennovel.reader.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Settings
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
import com.opennovel.reader.ui.screens.BrowseScreen
import com.opennovel.reader.ui.screens.LibraryScreen
import com.opennovel.reader.ui.screens.ReaderScreen
import com.opennovel.reader.ui.screens.SettingsScreen

private sealed class Dest(val route: String, val label: String) {
    data object Library : Dest("library", "Library")
    data object Browse : Dest("browse", "Browse")
    data object Settings : Dest("settings", "Settings")
}

private val bottomDests = listOf(Dest.Library, Dest.Browse, Dest.Settings)

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
                                        Dest.Browse -> Icons.Filled.Explore
                                        Dest.Settings -> Icons.Filled.Settings
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
                    onOpenChapter = { chapterId -> nav.navigate("reader/$chapterId") },
                )
            }
            composable(Dest.Browse.route) {
                BrowseScreen(factory = factory)
            }
            composable(Dest.Settings.route) {
                SettingsScreen(factory = factory)
            }
            composable("reader/{chapterId}") { entry ->
                val id = entry.arguments?.getString("chapterId")?.toLongOrNull() ?: return@composable
                ReaderScreen(chapterId = id, factory = factory, onBack = { nav.popBackStack() })
            }
        }
    }
}
