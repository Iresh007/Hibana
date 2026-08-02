package com.opennovel.reader.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.opennovel.reader.data.db.CategoryEntity
import com.opennovel.reader.data.db.NovelEntity
import com.opennovel.reader.ui.DEFAULT_CATEGORY_ID
import com.opennovel.reader.ui.LibrarySort
import com.opennovel.reader.ui.LibraryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    factory: ViewModelProvider.Factory,
    onOpenNovel: (Long) -> Unit,
) {
    val vm: LibraryViewModel = viewModel(factory = factory)
    val novels by vm.library.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selectedCategory by vm.selectedCategory.collectAsStateWithLifecycle()

    var showEditCategories by remember { mutableStateOf(false) }
    var assignTarget by remember { mutableStateOf<NovelEntity?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (novels.isEmpty()) "Library" else "Library (${novels.size})") },
            actions = {
                LibrarySortMenu(current = sort, onSelect = vm::setSort)
                LibraryOverflowMenu(onEditCategories = { showEditCategories = true })
            },
        )

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
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(novels, key = { it.id }) { novel ->
                    NovelCover(
                        novel = novel,
                        onClick = { onOpenNovel(novel.id) },
                        onLongClick = { assignTarget = novel },
                    )
                }
            }
        }
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

@Composable
private fun LibrarySortMenu(current: LibrarySort, onSelect: (LibrarySort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.Sort, contentDescription = "Sort library")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        LibrarySort.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(option.label) },
                onClick = { onSelect(option); expanded = false },
                trailingIcon = {
                    if (option == current) Icon(Icons.Filled.Check, contentDescription = null)
                },
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
            leadingIcon = { Icon(Icons.Filled.Label, contentDescription = null) },
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
                                Icon(Icons.Filled.Label, contentDescription = "Rename ${category.name}")
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
private fun NovelCover(novel: NovelEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Surface(
            tonalElevation = 2.dp,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatioCover(),
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
            text = novel.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
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
