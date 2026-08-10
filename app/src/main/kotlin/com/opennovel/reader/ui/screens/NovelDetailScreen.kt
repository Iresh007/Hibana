package com.opennovel.reader.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opennovel.reader.data.db.ContentType
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.opennovel.reader.data.ChapterGap
import com.opennovel.reader.data.findChapterGaps
import com.opennovel.reader.data.db.ChapterEntity
import com.opennovel.reader.data.db.NovelEntity
import com.opennovel.reader.download.QueueState
import com.opennovel.reader.ui.NovelDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelDetailScreen(
    novelId: Long,
    factory: ViewModelProvider.Factory,
    onOpenChapter: (Long) -> Unit,
    onBack: () -> Unit,
    onMigrate: (Long) -> Unit = {},
) {
    val vm: NovelDetailViewModel = viewModel(factory = factory)
    LaunchedEffect(novelId) { vm.load(novelId) }

    val novel by vm.novel.collectAsStateWithLifecycle()
    val chapters by vm.chapters.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()

    val inSelectionMode = selection.isNotEmpty()
    BackHandler(enabled = inSelectionMode) { vm.clearSelection() }

    // Recomputed only when the chapter list changes, not on every recomposition.
    val gaps = remember(chapters) { findChapterGaps(chapters) }
    val queueByChapter = remember(queue) { queue.associateBy { it.chapterId } }

    Column(Modifier.fillMaxSize()) {
        if (inSelectionMode) {
            TopAppBar(
                title = { Text("${selection.size} selected") },
                navigationIcon = {
                    IconButton(onClick = vm::clearSelection) {
                        Icon(Icons.Filled.Close, contentDescription = "Exit selection")
                    }
                },
                actions = {
                    IconButton(onClick = vm::selectAll) {
                        Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
                    }
                    IconButton(onClick = vm::invertSelection) {
                        Icon(Icons.Filled.FlipToBack, contentDescription = "Invert selection")
                    }
                },
            )
        } else {
            TopAppBar(
                title = { Text(novel?.title ?: "Details", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Single-title migration, without going via library selection.
                    if (novel?.inLibrary == true) {
                        IconButton(onClick = { onMigrate(novelId) }) {
                            Icon(Icons.Filled.SwapHoriz, contentDescription = "Migrate to another source")
                        }
                    }
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh chapters")
                    }
                    var typeMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { typeMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        val current = ContentType.from(novel?.contentType)
                        Text(
                            "Read this as",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        listOf(ContentType.COMIC to "Comic", ContentType.NOVEL to "Novel").forEach { (type, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { vm.setContentType(type); typeMenu = false },
                                leadingIcon = {
                                    if (current == type) {
                                        Icon(Icons.Filled.Check, contentDescription = null)
                                    }
                                },
                            )
                        }
                    }
                },
            )
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                novel?.let { NovelHeader(it, onToggleLibrary = vm::toggleLibrary) }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Chapters (${chapters.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (chapters.isNotEmpty()) {
                        Button(onClick = { vm.resume { id -> id?.let(onOpenChapter) } }) {
                            Text("Continue")
                        }
                    }
                }
            }
            if (chapters.isEmpty() && refreshing) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            items(chapters, key = { it.id }) { chapter ->
                // Warn before the chapter that follows a numbering gap.
                gaps[chapter.id]?.let { gap -> MissingChaptersDivider(gap) }
                ChapterRow(
                    chapter = chapter,
                    selected = chapter.id in selection,
                    selectionMode = inSelectionMode,
                    queueState = queueByChapter[chapter.id]?.state,
                    onOpen = {
                        if (inSelectionMode) vm.toggleSelection(chapter.id) else onOpenChapter(chapter.id)
                    },
                    onLongPress = { vm.toggleSelection(chapter.id) },
                    onToggleRead = { vm.markRead(chapter.id, !chapter.read) },
                    onToggleBookmark = { vm.setBookmark(chapter.id, !chapter.bookmark) },
                    onDownload = { vm.download(chapter.id) },
                    onCancelDownload = { vm.cancelDownload(chapter.id) },
                    onDeleteDownload = { vm.deleteDownload(chapter.id) },
                )
            }
        }

        if (inSelectionMode) {
            val anyDownloaded = chapters.any { it.id in selection && it.downloaded }
            BottomAppBar {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { vm.bookmarkSelected(true) }) {
                        Icon(Icons.Filled.Bookmark, contentDescription = "Bookmark selected")
                    }
                    IconButton(onClick = { vm.bookmarkSelected(false) }) {
                        Icon(Icons.Filled.BookmarkBorder, contentDescription = "Remove bookmark")
                    }
                    IconButton(onClick = { vm.markSelectedRead(true) }) {
                        Icon(Icons.Filled.DoneAll, contentDescription = "Mark as read")
                    }
                    IconButton(onClick = { vm.markSelectedRead(false) }) {
                        Icon(Icons.Filled.RemoveDone, contentDescription = "Mark as unread")
                    }
                    IconButton(onClick = vm::downloadSelected) {
                        Icon(Icons.Filled.Download, contentDescription = "Download selected")
                    }
                    // Only offered when there is something to remove, so the bar
                    // never presents an action that would silently do nothing.
                    if (anyDownloaded) {
                        IconButton(onClick = vm::deleteSelectedDownloads) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete downloads")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NovelHeader(novel: NovelEntity, onToggleLibrary: () -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Row {
            Surface(
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 2.dp,
                modifier = Modifier.width(120.dp).aspectRatioCover().clip(RoundedCornerShape(12.dp)),
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
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(novel.title, style = MaterialTheme.typography.titleLarge)
                novel.author?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                Text(
                    novel.status,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Button(onClick = onToggleLibrary, modifier = Modifier.padding(top = 12.dp)) {
                    Icon(
                        if (novel.inLibrary) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = null,
                    )
                    Text(
                        if (novel.inLibrary) "In library" else "Add to library",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        novel.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        if (novel.genres.isNotBlank()) {
            Text(
                novel.genres.split(",").joinToString(" · ") { it.trim() },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Marks chapters the source never listed, so a jump isn't mistaken for a bug. */
@Composable
private fun MissingChaptersDivider(gap: ChapterGap) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
        Text(
            gap.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
    }
}

/**
 * A chapter row that can be swiped either way.
 *
 * `confirmValueChange` always returns false: the gesture is a shortcut for a
 * toggle, not a dismissal, so the row must spring back rather than leave the
 * list — a chapter that vanished on swipe would look like data loss.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ChapterRow(
    chapter: ChapterEntity,
    selected: Boolean,
    selectionMode: Boolean,
    queueState: QueueState?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleBookmark: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
) {
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onToggleRead()
                SwipeToDismissBoxValue.EndToStart -> onToggleBookmark()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
    )

    SwipeToDismissBox(
        state = swipeState,
        // Selection mode owns the gesture space; swiping while picking chapters
        // would fire toggles the user never intended.
        gesturesEnabled = !selectionMode,
        backgroundContent = {
            val toEnd = swipeState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val colour = if (toEnd) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            }
            val icon: ImageVector = if (toEnd) {
                if (chapter.read) Icons.Filled.RemoveDone else Icons.Filled.Done
            } else {
                if (chapter.bookmark) Icons.Filled.BookmarkBorder else Icons.Filled.Bookmark
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .background(colour)
                    .padding(horizontal = 20.dp),
                contentAlignment = if (toEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (toEnd) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    },
                )
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                )
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (chapter.bookmark) {
                Icon(
                    Icons.Filled.Bookmark,
                    contentDescription = "Bookmarked",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp).padding(end = 2.dp),
                )
            }
            Column(Modifier.weight(1f).padding(start = if (chapter.bookmark) 6.dp else 0.dp)) {
                Text(
                    chapter.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (chapter.read) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                val meta = buildList {
                    chapter.dateUpload.takeIf { it > 0 }?.let { add(formatUploadDate(it)) }
                    when (queueState) {
                        QueueState.QUEUED -> add("Queued")
                        QueueState.DOWNLOADING -> add("Downloading…")
                        QueueState.FAILED -> add("Download failed")
                        else -> if (chapter.downloaded) add("Downloaded")
                    }
                }
                if (meta.isNotEmpty()) {
                    Text(
                        meta.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
            }
            ChapterDownloadButton(
                downloaded = chapter.downloaded,
                queueState = queueState,
                onDownload = onDownload,
                onCancel = onCancelDownload,
                onDelete = onDeleteDownload,
            )
        }
    }
}

/**
 * Download affordance with a long-press menu.
 *
 * Built from a Box rather than an IconButton because IconButton has no
 * long-press hook, and the destructive "delete download" action must not be
 * reachable by an accidental tap.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterDownloadButton(
    downloaded: Boolean,
    queueState: QueueState?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Box {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .combinedClickable(
                    onClick = { if (!downloaded && queueState == null) onDownload() },
                    onLongClick = { if (downloaded) confirmDelete = true else menuOpen = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                queueState == QueueState.DOWNLOADING -> CircularProgressIndicator(Modifier.size(20.dp))
                downloaded -> Icon(Icons.Filled.DownloadDone, contentDescription = "Downloaded")
                else -> Icon(
                    Icons.Filled.Download,
                    contentDescription = if (queueState == QueueState.QUEUED) "Queued" else "Download chapter",
                    tint = if (queueState == QueueState.QUEUED) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Download now") },
                onClick = { onDownload(); menuOpen = false },
            )
            DropdownMenuItem(
                text = { Text("Cancel") },
                onClick = { onCancel(); menuOpen = false },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this download?") },
            text = { Text("The chapter stays in the list but will need to be fetched again.") },
            confirmButton = {
                TextButton(onClick = { onDelete(); confirmDelete = false }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
            },
        )
    }
}

/** Recent uploads read better as "2 days ago"; older ones as a date. */
private fun formatUploadDate(millis: Long): String {
    val days = ((System.currentTimeMillis() - millis) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "Today"
        days == 1 -> "Yesterday"
        days < 30 -> "$days days ago"
        else -> java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
            .format(java.util.Date(millis))
    }
}
