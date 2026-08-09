package com.opennovel.reader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opennovel.reader.ui.LibraryStats
import com.opennovel.reader.ui.StatsViewModel
import java.text.DateFormat
import java.util.Date

/**
 * Mihon's Statistics screen: what the library actually contains and how much of
 * it has been read. Every figure is computed from stored data — nothing here is
 * an estimate except where the card says so.
 *
 * [factory] is accepted for call-site consistency with the other screens; the
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    factory: ViewModelProvider.Factory,
    onBack: () -> Unit,
) {
    val vm: StatsViewModel = viewModel(factory = factory)
    val stats by vm.stats.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Statistics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { StatsSection("Library", libraryCards(stats)) }
            item { StatsSection("Chapters", chapterCards(stats)) }
            item {
                Text(
                    "Bookmarks are counted across recent updates and downloads only.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item {
                StatsSection("Reading", readingCards(stats))
                LastReadRow(stats.lastReadTitle)
            }
            item { SourcesSection(stats) }
        }
    }
}

/** A label/value pair rendered as one card. */
private data class Stat(val label: String, val value: String)

private fun libraryCards(s: LibraryStats) = listOf(
    Stat("Entries", s.totalEntries.toString()),
    Stat("Comics", s.comics.toString()),
    Stat("Novels", s.novels.toString()),
    Stat("Untyped", s.untypedEntries.toString()),
    Stat("Categories", s.categories.toString()),
    Stat("With unread", s.withUnread.toString()),
    Stat("Started", s.started.toString()),
    Stat("Finished", s.finished.toString()),
)

private fun chapterCards(s: LibraryStats) = listOf(
    Stat("Total", s.totalChapters.toString()),
    Stat("Read", s.readChapters.toString()),
    Stat("Unread", s.unreadChapters.toString()),
    Stat("Downloaded", s.downloadedChapters.toString()),
    Stat("Bookmarked*", s.bookmarkedChaptersSeen.toString()),
)

private fun readingCards(s: LibraryStats) = listOf(
    Stat("Entries opened", s.entriesRead.toString()),
    Stat("Read this week", s.entriesReadLastWeek.toString()),
    Stat(
        "Last read",
        if (s.lastReadAt > 0L) {
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.lastReadAt))
        } else {
            "Never"
        },
    ),
)

@Composable
private fun StatsSection(title: String, stats: List<Stat>) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        // Two per row, laid out manually: a nested LazyVerticalGrid inside a
        // LazyColumn cannot measure its own height and crashes at runtime.
        stats.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { stat ->
                    StatCard(stat, Modifier.weight(1f))
                }
                // Keeps an odd final card at half width rather than stretching it.
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatCard(stat: Stat, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                stat.value,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stat.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourcesSection(stats: LibraryStats) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "Sources",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        StatCard(Stat("Sources in use", stats.distinctSources.toString()), Modifier.fillMaxWidth())
        if (stats.topSources.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    stats.topSources.forEach { source ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            Text(
                                source.sourceName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                source.count.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Most recently read entry, on its own row so long titles get full width. */
@Composable
private fun LastReadRow(title: String?) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            "Most recently read",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Text(
            title ?: "Nothing read yet",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
