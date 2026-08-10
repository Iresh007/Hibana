package com.opennovel.reader.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.opennovel.reader.ui.SourceBrowseViewModel
import kotlinx.coroutines.launch

/**
 * Browses a single source, reached by long-pressing an installed extension.
 *
 * Popular and Latest are separate tabs because they answer different questions —
 * "what's worth reading here" versus "what just updated". Sources with no real
 * latest feed hide the tab rather than showing a duplicate of Popular.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceBrowseScreen(
    sourceId: Long,
    factory: ViewModelProvider.Factory,
    onOpenNovel: (Long) -> Unit,
    onBack: () -> Unit,
    /**
     * Opens straight onto Latest instead of Popular. The browse list offers both
     * as separate entry points, and landing on Popular after tapping "Latest"
     * would silently ignore the choice. Defaults to false so the plain
     * navigation route is unaffected.
     */
    initialLatest: Boolean = false,
) {
    val vm: SourceBrowseViewModel = viewModel(factory = factory)
    val results by vm.results.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val sourceName by vm.sourceName.collectAsStateWithLifecycle()
    val supportsLatest by vm.supportsLatest.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var tab by remember(sourceId) { mutableIntStateOf(if (initialLatest) 1 else 0) }

    LaunchedEffect(sourceId) { vm.bind(sourceId) }
    LaunchedEffect(sourceId, tab) { if (tab == 0) vm.loadPopular() else vm.loadLatest() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(sourceName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        if (supportsLatest) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Popular") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Latest") })
            }
        }

        when {
            loading && results.isEmpty() ->
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

            results.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    "Nothing found in this source.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(results, key = { it.url }) { novel ->
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                scope.launch { vm.cacheForDetails(novel)?.let(onOpenNovel) }
                            },
                    ) {
                        Surface(
                            tonalElevation = 2.dp,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().aspectRatioCover(),
                        ) {
                            if (novel.coverUrl != null) {
                                AsyncImage(
                                    model = novel.coverUrl,
                                    contentDescription = novel.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Box(Modifier.fillMaxSize(), Alignment.Center) {
                                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                                }
                            }
                        }
                        Text(
                            novel.title,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
