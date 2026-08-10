package com.opennovel.reader.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opennovel.reader.ui.BrowseViewModel
import com.opennovel.reader.ui.SectionScopeViewModel

/**
 * Browse hub with Mihon's three tabs.
 *
 * They're grouped because they're the same task at different stages: find a
 * source, install more sources, or move existing entries between them. Keeping
 * Migrate here (as well as the library's selection bar) matches Mihon, where you
 * migrate everything off a dead source by picking that source rather than
 * hunting its entries in the library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseHostScreen(
    factory: ViewModelProvider.Factory,
    onOpenNovel: (Long) -> Unit,
    onOpenRepos: () -> Unit,
    onBrowseSource: (Long) -> Unit,
    onMigrateFromSource: (Long) -> Unit,
    onGlobalSearch: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Browse") },
            actions = {
                // Global search spans every source, so it belongs above the tabs
                // rather than inside the per-source Sources tab.
                IconButton(onClick = onGlobalSearch) {
                    Icon(Icons.Filled.TravelExplore, contentDescription = "Search all sources")
                }
            },
        )
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Sources") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Extensions") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Migrate") })
        }

        when (tab) {
            0 -> BrowseScreen(factory = factory, onOpenNovel = onOpenNovel)
            1 -> ExtensionsScreen(
                factory = factory,
                onOpenRepos = onOpenRepos,
                onBrowseSource = onBrowseSource,
            )
            else -> MigrateSourcePicker(factory = factory, onPickSource = onMigrateFromSource)
        }
    }
}

/**
 * Lists sources that library entries currently come from, so migration starts
 * from "this source is broken" rather than from individual titles.
 */
@Composable
private fun MigrateSourcePicker(
    factory: ViewModelProvider.Factory,
    onPickSource: (Long) -> Unit,
) {
    val context = LocalContext.current
    val vm: BrowseViewModel = viewModel(factory = factory)
    val sectionVm: SectionScopeViewModel =
        viewModel(factory = remember(context) { SectionScopeViewModel.factory(context) })
    val allSourcesInUse by vm.librarySources.collectAsStateWithLifecycle()
    val section by sectionVm.section.collectAsStateWithLifecycle()
    val sectionSourceIds by sectionVm.sourceIds.collectAsStateWithLifecycle()

    // Migration only ever moves an entry sideways within its own section, so the
    // other section's sources are not offered as starting points either.
    val sourcesInUse = remember(allSourcesInUse, sectionSourceIds) {
        allSourcesInUse.filter { it.sourceId in sectionSourceIds }
    }

    if (sourcesInUse.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(
                "No ${section.label} sources in use.\n" +
                    "Add something to your library first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }

    LazyColumn {
        items(sourcesInUse, key = { it.sourceId }) { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPickSource(entry.sourceId) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.sourceName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${entry.count} in library",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
}
