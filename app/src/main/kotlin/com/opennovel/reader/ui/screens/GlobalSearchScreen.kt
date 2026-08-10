package com.opennovel.reader.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.opennovel.reader.source.model.SNovel
import com.opennovel.reader.ui.BrowseViewModel
import com.opennovel.reader.ui.SectionScopeViewModel
import com.opennovel.reader.ui.SourceSearchResult
import kotlinx.coroutines.launch

/**
 * Searches every installed source at once and lets results be added to the
 * library directly.
 *
 * Results are grouped per source, and each group renders as soon as *that*
 * source answers rather than waiting for the slowest one — with a dozen
 * extensions installed, a single combined list would stall behind whichever
 * source is worst.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearchScreen(
    factory: ViewModelProvider.Factory,
    onOpenNovel: (Long) -> Unit,
    onBack: () -> Unit,
    initialQuery: String = "",
) {
    val context = LocalContext.current
    val vm: BrowseViewModel = viewModel(factory = factory)
    val sectionVm: SectionScopeViewModel =
        viewModel(factory = remember(context) { SectionScopeViewModel.factory(context) })
    val allResults by vm.global.collectAsStateWithLifecycle()
    val section by sectionVm.section.collectAsStateWithLifecycle()
    val sectionSourceIds by sectionVm.sourceIds.collectAsStateWithLifecycle()

    // Scoped to the active section for the same reason Browse is: a result the
    // section cannot open is worse than no result.
    val results = remember(allResults, sectionSourceIds) {
        allResults.filter { it.sourceId in sectionSourceIds }
    }
    val scope = rememberCoroutineScope()
    val added = remember { mutableStateMapOf<String, Boolean>() }

    var query by remember { mutableStateOf(initialQuery) }

    LaunchedEffect(Unit) {
        vm.setGlobalMode(true)
        if (initialQuery.isNotBlank()) vm.globalSearch(initialQuery)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Global search") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Search all ${section.label} sources") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        LaunchedEffect(query) {
            if (query.length >= 2) vm.globalSearch(query)
        }

        when {
            results.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    if (query.length < 2) {
                        "Type at least two characters to search every " +
                            "${section.label} source."
                    } else {
                        "Searching…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(32.dp),
                )
            }

            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(results, key = { it.sourceId }) { section ->
                    SourceSection(
                        section = section,
                        isAdded = { added["${section.sourceId}-${it.url}"] == true },
                        onOpen = { novel ->
                            scope.launch { onOpenNovel(vm.cacheForDetails(section.sourceId, novel)) }
                        },
                        onAdd = { novel ->
                            scope.launch {
                                val id = vm.cacheForDetails(section.sourceId, novel)
                                vm.addExistingToLibrary(id)
                                added["${section.sourceId}-${novel.url}"] = true
                            }
                        },
                    )
                }
            }
        }
    }
}

/** One source's results as a horizontal strip, so many sources fit on screen. */
@Composable
private fun SourceSection(
    section: SourceSearchResult,
    isAdded: (SNovel) -> Boolean,
    onOpen: (SNovel) -> Unit,
    onAdd: (SNovel) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                section.sourceName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when {
                section.loading -> CircularProgressIndicator(Modifier.size(16.dp))
                section.error != null -> Text(
                    "failed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                section.novels.isEmpty() -> Text(
                    "no results",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                else -> Text(
                    "${section.novels.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }

        if (section.novels.isNotEmpty()) {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(section.novels, key = { it.url }) { novel ->
                    Column(
                        Modifier
                            .width(104.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpen(novel) },
                    ) {
                        Box {
                            Surface(
                                tonalElevation = 2.dp,
                                shape = RoundedCornerShape(10.dp),
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
                            // Add directly from the result — the common action
                            // after a global search is "put this in my library".
                            IconButton(
                                onClick = { onAdd(novel) },
                                enabled = !isAdded(novel),
                                modifier = Modifier.align(Alignment.TopEnd),
                            ) {
                                Icon(
                                    if (isAdded(novel)) Icons.Filled.Check else Icons.Filled.Add,
                                    contentDescription = if (isAdded(novel)) {
                                        "In library"
                                    } else {
                                        "Add ${novel.title} to library"
                                    },
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Text(
                            novel.title,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
