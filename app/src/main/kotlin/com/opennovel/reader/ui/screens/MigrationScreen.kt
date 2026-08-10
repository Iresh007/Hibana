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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.opennovel.reader.migration.MigrationCandidate
import com.opennovel.reader.migration.MigrationOptions
import com.opennovel.reader.migration.MigrationSearch
import com.opennovel.reader.ui.MigrationFlowViewModel

/**
 * The migration wizard: choose entries, choose what carries across, choose where
 * to look, then review every match before anything is written.
 *
 * Jumping straight to a search — which is what this screen used to do — hid the
 * two decisions that actually matter. Migration rewrites reading progress and
 * can retire the original entry, so each step is explicit and nothing is
 * committed until the final confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationScreen(
    novelIds: List<Long>,
    factory: ViewModelProvider.Factory,
    onBack: () -> Unit,
    /**
     * Set when migrating a whole source, in which case the wizard opens on entry
     * selection. A library selection already names its entries and skips it.
     */
    sourceId: Long? = null,
) {
    val context = LocalContext.current
    val vm: MigrationFlowViewModel = viewModel(
        factory = remember(context) { MigrationFlowViewModel.factory(context) },
    )

    val entries by vm.entries.collectAsStateWithLifecycle()
    val checked by vm.checked.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val targetSources by vm.targetSources.collectAsStateWithLifecycle()
    val searches by vm.searches.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()

    // Coming from a library selection there is nothing to choose, so start at
    // the options step rather than showing a one-item list.
    var step by remember { mutableStateOf(if (sourceId == null) Step.OPTIONS else Step.ENTRIES) }

    LaunchedEffect(sourceId) { sourceId?.let(vm::loadEntries) }
    LaunchedEffect(done) { if (done) onBack() }

    val chosenIds = if (sourceId == null) novelIds else checked.toList()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(step.title)
                        Text(
                            progress ?: "Step ${step.ordinal + 1} of 4",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        // Back walks the wizard rather than leaving it, so a
                        // mis-tap on step four doesn't discard the whole setup.
                        step = when (step) {
                            Step.ENTRIES -> return@IconButton onBack()
                            Step.OPTIONS -> if (sourceId == null) return@IconButton onBack() else Step.ENTRIES
                            Step.SOURCES -> Step.OPTIONS
                            Step.REVIEW -> Step.SOURCES
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())

            when (step) {
                Step.ENTRIES -> EntriesStep(
                    entries = entries,
                    checked = checked,
                    onToggle = vm::toggleChecked,
                    onSetAll = vm::setAllChecked,
                    onNext = { step = Step.OPTIONS },
                    modifier = Modifier.weight(1f),
                )

                Step.OPTIONS -> OptionsStep(
                    options = options,
                    count = chosenIds.size,
                    onChange = vm::setOptions,
                    onNext = { step = Step.SOURCES },
                    modifier = Modifier.weight(1f),
                )

                Step.SOURCES -> SourcesStep(
                    vm = vm,
                    allSelected = targetSources == null,
                    onNext = {
                        step = Step.REVIEW
                        vm.search(chosenIds)
                    },
                    modifier = Modifier.weight(1f),
                )

                Step.REVIEW -> ReviewStep(
                    searches = searches,
                    selected = selected,
                    searching = searching,
                    onChoose = vm::choose,
                    onSkip = vm::skip,
                    onConfirm = vm::migrateSelected,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private enum class Step(val title: String) {
    ENTRIES("Select entries"),
    OPTIONS("Migration options"),
    SOURCES("Search which sources"),
    REVIEW("Review matches"),
}

@Composable
private fun EntriesStep(
    entries: List<com.opennovel.reader.ui.MigrateEntry>?,
    checked: Set<Long>,
    onToggle: (Long) -> Unit,
    onSetAll: (Boolean) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries == null) {
        Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${checked.size} of ${entries.size} selected", style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = { onSetAll(true) }) { Text("All") }
                TextButton(onClick = { onSetAll(false) }) { Text("None") }
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f)) {
            items(entries, key = { it.id }) { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(entry.id) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = entry.id in checked, onCheckedChange = { onToggle(entry.id) })
                    Cover(entry.coverUrl)
                    Text(
                        entry.title,
                        Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        StepButton("Next", enabled = checked.isNotEmpty(), onClick = onNext)
    }
}

@Composable
private fun OptionsStep(
    options: MigrationOptions,
    count: Int,
    onChange: (MigrationOptions) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Migrating $count ${if (count == 1) "entry" else "entries"}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "Choose what to bring across.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 8.dp),
            )

            CheckRow("Chapters read", options.chaptersRead) { onChange(options.copy(chaptersRead = it)) }
            CheckRow("Categories", options.categories) { onChange(options.copy(categories = it)) }
            CheckRow("Bookmarks", options.bookmarks) { onChange(options.copy(bookmarks = it)) }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Row(Modifier.fillMaxWidth().clickable { onChange(options.copy(removeOriginal = true)) },
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = options.removeOriginal, onClick = { onChange(options.copy(removeOriginal = true)) })
                Column(Modifier.padding(start = 4.dp)) {
                    Text("Move", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Removes the original from your library",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().clickable { onChange(options.copy(removeOriginal = false)) },
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = !options.removeOriginal, onClick = { onChange(options.copy(removeOriginal = false)) })
                Column(Modifier.padding(start = 4.dp)) {
                    Text("Copy", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Keeps both entries shelved",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
        StepButton("Next", enabled = count > 0, onClick = onNext)
    }
}

@Composable
private fun SourcesStep(
    vm: MigrationFlowViewModel,
    allSelected: Boolean,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (allSelected) "Searching all sources" else "Searching selected sources",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = vm::useAllSources) { Text("All") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f)) {
            items(vm.availableSources, key = { it.sourceId }) { source ->
                val on = vm.isSourceSelected(source.sourceId)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.toggleTargetSource(source.sourceId) }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = on, onCheckedChange = { vm.toggleTargetSource(source.sourceId) })
                    Text(source.sourceName, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        StepButton("Search", enabled = true, onClick = onNext)
    }
}

@Composable
private fun ReviewStep(
    searches: List<MigrationSearch>,
    selected: Map<Long, MigrationCandidate>,
    searching: Boolean,
    onChoose: (Long, MigrationCandidate) -> Unit,
    onSkip: (Long) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (searches.isEmpty() && searching) {
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                items(searches, key = { it.novel.id }) { search ->
                    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(search.novel.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Currently ${search.currentChapterCount} chapters",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                            if (search.candidates.isEmpty()) {
                                Text(
                                    "No match found",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            } else {
                                search.candidates.forEach { candidate ->
                                    val isChosen = selected[search.novel.id] == candidate
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { onChoose(search.novel.id, candidate) }
                                            .padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = isChosen,
                                            onClick = { onChoose(search.novel.id, candidate) },
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(candidate.sourceName, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                buildString {
                                                    append(candidate.novel.title)
                                                    if (candidate.chapterCount >= 0) {
                                                        // The comparison is the whole point: a
                                                        // candidate with far fewer chapters is
                                                        // usually the wrong work, or a dead mirror.
                                                        append(" · ${candidate.chapterCount} chapters")
                                                        val delta = candidate.chapterCount - search.currentChapterCount
                                                        if (delta != 0) append(if (delta > 0) " (+$delta)" else " ($delta)")
                                                    }
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Text(
                                            "${(candidate.score * 100).toInt()}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                TextButton(onClick = { onSkip(search.novel.id) }) { Text("Skip this one") }
                            }
                        }
                    }
                }
            }
        }
        StepButton(
            label = "Migrate ${selected.size} ${if (selected.size == 1) "entry" else "entries"}",
            enabled = selected.isNotEmpty() && !searching,
            onClick = onConfirm,
        )
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) { Text(label) }
}

@Composable
private fun Cover(url: String?) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.size(width = 32.dp, height = 44.dp).clip(RoundedCornerShape(4.dp)),
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop)
        }
    }
}
