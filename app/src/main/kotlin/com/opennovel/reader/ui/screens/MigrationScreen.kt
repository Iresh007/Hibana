package com.opennovel.reader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opennovel.reader.migration.MigrationCandidate
import com.opennovel.reader.migration.MigrationSearch
import com.opennovel.reader.ui.MigrationViewModel

/**
 * Preview-and-confirm migration for one or many entries.
 *
 * Every entry is shown with its current chapter count beside each candidate's,
 * because that comparison is what tells the user whether the new source is
 * actually better. Nothing moves until they press Migrate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationScreen(
    novelIds: List<Long>,
    factory: ViewModelProvider.Factory,
    onBack: () -> Unit,
) {
    val vm: MigrationViewModel = viewModel(factory = factory)
    val searches by vm.searches.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()

    LaunchedEffect(novelIds) { vm.search(novelIds) }
    LaunchedEffect(done) { if (done) onBack() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(progress ?: "Migrate ${novelIds.size} title(s)") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                TextButton(
                    onClick = { vm.migrateSelected() },
                    enabled = !searching && selected.isNotEmpty(),
                ) { Text("Migrate (${selected.size})") }
            },
        )

        when {
            searching && searches.isEmpty() ->
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            "Searching other sources…",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }

            searches.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    "No matches found on other sources.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            else -> LazyColumn {
                items(searches, key = { it.novel.id }) { search ->
                    MigrationCard(
                        search = search,
                        chosen = selected[search.novel.id],
                        onChoose = { vm.choose(search.novel.id, it) },
                        onSkip = { vm.skip(search.novel.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun MigrationCard(
    search: MigrationSearch,
    chosen: MigrationCandidate?,
    onChoose: (MigrationCandidate) -> Unit,
    onSkip: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            search.novel.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Currently ${search.currentChapterCount} chapters",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )

        if (search.candidates.isEmpty()) {
            Text(
                "No match found on other sources",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Column
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                search.candidates.forEach { candidate ->
                    CandidateRow(
                        candidate = candidate,
                        currentCount = search.currentChapterCount,
                        selected = chosen == candidate,
                        onClick = { onChoose(candidate) },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onSkip, enabled = chosen != null) { Text("Skip this title") }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: MigrationCandidate,
    currentCount: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickableMinTouch(onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                candidate.sourceName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                candidate.novel.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            // The number the decision actually hinges on.
            val count = candidate.chapterCount
            Text(
                if (count >= 0) "$count ch" else "? ch",
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    count < 0 -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    count >= currentCount -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                },
            )
            Text(
                "${(candidate.score * 100).toInt()}% match",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}
