package com.opennovel.reader.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.opennovel.reader.data.LibraryDisplayMode
import com.opennovel.reader.data.db.CategoryEntity
import com.opennovel.reader.data.db.NovelEntity
import com.opennovel.reader.ui.DEFAULT_CATEGORY_ID
import com.opennovel.reader.ui.FilterState
import com.opennovel.reader.ui.LibraryFilters
import com.opennovel.reader.ui.LibrarySort
import com.opennovel.reader.ui.LibraryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    factory: ViewModelProvider.Factory,
    onOpenNovel: (Long) -> Unit,
    onMigrate: (List<Long>) -> Unit = {},
) {
    val vm: LibraryViewModel = viewModel(factory = factory)
    val novels by vm.library.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selectedCategory by vm.selectedCategory.collectAsStateWithLifecycle()

    var showEditCategories by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var assignTarget by remember { mutableStateOf<NovelEntity?>(null) }

    val selection by vm.selection.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val inSelectionMode = selection.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        if (inSelectionMode) {
            // Contextual bar, so batch actions never crowd the normal toolbar.
            TopAppBar(
                title = { Text("${selection.size} selected") },
                navigationIcon = {
                    IconButton(onClick = vm::clearSelection) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                    }
                },
                actions = {
                    IconButton(onClick = vm::selectAllVisible) {
                        Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
                    }
                    IconButton(
                        onClick = { novels.firstOrNull { it.id in selection }?.let { assignTarget = it } },
                        enabled = selection.size == 1,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Label, contentDescription = "Set categories")
                    }
                    IconButton(onClick = { onMigrate(selection.toList()); vm.clearSelection() }) {
                        Icon(Icons.Filled.SwapHoriz, contentDescription = "Migrate to another source")
                    }
                },
            )
        } else {
            TopAppBar(
                title = { Text(if (novels.isEmpty()) "Library" else "Library (${novels.size})") },
                actions = {
                    IconButton(onClick = { showFilterSheet = true }) {
                        Icon(
                            Icons.Filled.FilterList,
                            contentDescription = "Filter, sort and display",
                            // Tinting when filtered stops a forgotten filter from
                            // silently explaining an apparently empty library.
                            tint = if (filters.active > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            },
                        )
                    }
                    LibraryOverflowMenu(onEditCategories = { showEditCategories = true })
                },
            )
        }

        // Category tabs (Mihon-style) — only when the user has created categories.
        if (categories.isNotEmpty()) {
            val tabs = listOf(CategoryEntity(DEFAULT_CATEGORY_ID, "Default")) + categories
            val selectedIndex = tabs.indexOfFirst { it.id == selectedCategory }.coerceAtLeast(0)
            ScrollableTabRow(selectedTabIndex = selectedIndex, edgePadding = 12.dp) {
                tabs.forEachIndexed { index, category ->
                    Tab(
                        selected = index == selectedIndex,
                        onClick = { vm.selectCategory(category.id) },
                        text = { Text(category.name) },
                    )
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = vm::setQuery,
            singleLine = true,
            placeholder = { Text("Search library") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        )

        when {
            novels.isEmpty() && query.isNotBlank() -> NoResults(query)
            novels.isEmpty() -> EmptyLibrary()
            else -> {
                // Grid density / list layout, as Mihon offers.
                val cellSize = when (settings.libraryDisplayMode) {
                    LibraryDisplayMode.COMPACT_GRID -> 92.dp
                    else -> 120.dp
                }
                val spacing = if (settings.libraryDisplayMode == LibraryDisplayMode.COMPACT_GRID) 6.dp else 12.dp
                LazyVerticalGrid(
                    columns = if (settings.libraryDisplayMode == LibraryDisplayMode.LIST) {
                        GridCells.Fixed(1)
                    } else {
                        GridCells.Adaptive(minSize = cellSize)
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(spacing),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    items(novels, key = { it.id }) { novel ->
                        val badge = counts[novel.id]
                        val onTap = {
                            if (inSelectionMode) vm.toggleSelection(novel.id) else onOpenNovel(novel.id)
                        }
                        if (settings.libraryDisplayMode == LibraryDisplayMode.LIST) {
                            NovelListRow(
                                novel = novel,
                                unread = badge?.unread ?: 0,
                                downloaded = badge?.downloaded ?: 0,
                                showBadges = settings.showLibraryBadges,
                                selected = novel.id in selection,
                                onClick = onTap,
                                onLongClick = { vm.toggleSelection(novel.id) },
                            )
                        } else {
                            NovelCover(
                                novel = novel,
                                selected = novel.id in selection,
                                unread = badge?.unread ?: 0,
                                downloaded = badge?.downloaded ?: 0,
                                showBadges = settings.showLibraryBadges,
                                // Long-press starts selection; once in selection
                                // mode a tap toggles rather than opening.
                                onClick = onTap,
                                onLongClick = { vm.toggleSelection(novel.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        LibraryFilterSheet(
            filters = filters,
            sort = sort,
            settings = settings,
            vm = vm,
            onDismiss = { showFilterSheet = false },
        )
    }

    if (showEditCategories) {
        EditCategoriesDialog(
            categories = categories,
            onCreate = vm::createCategory,
            onRename = vm::renameCategory,
            onDelete = vm::deleteCategory,
            onDismiss = { showEditCategories = false },
        )
    }

    assignTarget?.let { novel ->
        AssignCategoriesDialog(
            categories = categories,
            loadCurrent = { cb -> vm.categoryIdsForNovel(novel.id, cb) },
            onConfirm = { ids -> vm.setNovelCategories(novel.id, ids); assignTarget = null },
            onEditCategories = { assignTarget = null; showEditCategories = true },
            onDismiss = { assignTarget = null },
        )
    }
}

/**
 * Mihon's combined Filter / Sort / Display sheet.
 *
 * One sheet with three tabs rather than three toolbar menus: these are adjusted
 * together while looking at the grid, and a bottom sheet keeps the library
 * visible behind it so each change shows its effect immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryFilterSheet(
    filters: LibraryFilters,
    sort: LibrarySort,
    settings: com.opennovel.reader.data.ReaderSettings,
    vm: LibraryViewModel,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Filter") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Sort") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Display") })
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (tab) {
                0 -> {
                    Text(
                        "Tap to cycle: off → only → exclude",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    FilterRow("Downloaded", filters.downloaded, vm::cycleDownloadedFilter)
                    FilterRow("Unread", filters.unread, vm::cycleUnreadFilter)
                    FilterRow("Started", filters.started, vm::cycleStartedFilter)
                    if (filters.active > 0) {
                        TextButton(onClick = vm::clearFilters) { Text("Clear filters") }
                    }
                }

                1 -> LibrarySort.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clickableMinTouch { vm.setSort(option) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = sort == option, onClick = null)
                        Text(option.label, Modifier.padding(start = 8.dp))
                    }
                }

                else -> {
                    Text("Layout", style = MaterialTheme.typography.labelLarge)
                    LibraryDisplayMode.entries.forEach { mode ->
                        Row(
                            Modifier.fillMaxWidth().clickableMinTouch { vm.setLibraryDisplayMode(mode) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = settings.libraryDisplayMode == mode, onClick = null)
                            Text(mode.label, Modifier.padding(start = 8.dp))
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Badges", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Unread and downloaded counts", Modifier.weight(1f))
                        Switch(
                            checked = settings.showLibraryBadges,
                            onCheckedChange = vm::setShowLibraryBadges,
                        )
                    }
                }
            }
        }
    }
}

/** One tri-state filter row; tapping cycles off → only → exclude. */
@Composable
private fun FilterRow(label: String, state: FilterState, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickableMinTouch(onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        when (state) {
            FilterState.IGNORED -> Unit
            FilterState.INCLUDED -> Icon(
                Icons.Filled.Check,
                contentDescription = "Only $label",
                tint = MaterialTheme.colorScheme.primary,
            )
            // A distinct icon, not just a colour change: "exclude" is easy to
            // mistake for "include" when only the tint differs.
            FilterState.EXCLUDED -> Icon(
                Icons.Filled.Block,
                contentDescription = "Exclude $label",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun LibraryOverflowMenu(onEditCategories: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Edit categories") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
            onClick = { onEditCategories(); expanded = false },
        )
    }
}

@Composable
private fun EditCategoriesDialog(
    categories: List<CategoryEntity>,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var editName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Categories") },
        text = {
            Column {
                categories.forEach { category ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (editing?.id == category.id) {
                            OutlinedTextField(
                                value = editName,
                                onValueChange = { editName = it },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onRename(category.id, editName); editing = null }) {
                                Text("Save")
                            }
                        } else {
                            Text(category.name, modifier = Modifier.weight(1f))
                            IconButton(onClick = { editing = category; editName = category.name }) {
                                Icon(Icons.AutoMirrored.Filled.Label, contentDescription = "Rename ${category.name}")
                            }
                            IconButton(onClick = { onDelete(category.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete ${category.name}")
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        placeholder = { Text("New category") },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onCreate(newName); newName = "" },
                        enabled = newName.isNotBlank(),
                    ) { Text("Add") }
                }
            }
        },
    )
}

@Composable
private fun AssignCategoriesDialog(
    categories: List<CategoryEntity>,
    loadCurrent: ((Set<Long>) -> Unit) -> Unit,
    onConfirm: (Set<Long>) -> Unit,
    onEditCategories: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    remember { loadCurrent { selected = it } }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Set categories") },
        text = {
            if (categories.isEmpty()) {
                Column {
                    Text("No categories yet.")
                    OutlinedButton(onClick = onEditCategories, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Create a category")
                    }
                }
            } else {
                Column {
                    categories.forEach { category ->
                        val checked = category.id in selected
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickableMinTouch {
                                    selected = if (checked) selected - category.id else selected + category.id
                                },
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(category.name, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun NoResults(query: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(
            "No novels match \"$query\"",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovelCover(
    novel: NovelEntity,
    selected: Boolean = false,
    unread: Int = 0,
    downloaded: Int = 0,
    showBadges: Boolean = true,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            Surface(
                tonalElevation = 2.dp,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatioCover()
                    // A border reads as "picked" at grid size better than a tint.
                    .then(
                        if (selected) {
                            Modifier.border(
                                3.dp,
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(12.dp),
                            )
                        } else {
                            Modifier
                        },
                    ),
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
            if (showBadges && (unread > 0 || downloaded > 0)) {
                LibraryBadges(
                    unread = unread,
                    downloaded = downloaded,
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }
        }
        Text(
            text = novel.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/**
 * Unread and downloaded counts on a cover — the at-a-glance signal Mihon users
 * rely on to see what's worth opening without entering each entry.
 */
@Composable
private fun LibraryBadges(unread: Int, downloaded: Int, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(4.dp))) {
        if (unread > 0) {
            Text(
                unread.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        if (downloaded > 0) {
            Text(
                downloaded.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiary,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.tertiary)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}

/** Denser one-line layout for [LibraryDisplayMode.LIST]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovelListRow(
    novel: NovelEntity,
    unread: Int,
    downloaded: Int,
    showBadges: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                } else {
                    Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.width(40.dp).aspectRatioCover().clip(RoundedCornerShape(6.dp)),
        ) {
            if (novel.coverUrl != null) {
                AsyncImage(
                    model = novel.coverUrl,
                    contentDescription = novel.title,
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                }
            }
        }
        Text(
            novel.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        if (showBadges) LibraryBadges(unread = unread, downloaded = downloaded)
    }
}

@Composable
private fun EmptyLibrary() {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                modifier = Modifier.padding(8.dp),
            )
            Text("Your library is empty", style = MaterialTheme.typography.titleMedium)
            Text(
                "Head to Browse to add novels",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

