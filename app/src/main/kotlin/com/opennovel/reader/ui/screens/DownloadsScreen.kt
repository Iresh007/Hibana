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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opennovel.reader.download.QueueState
import com.opennovel.reader.download.QueuedDownload
import com.opennovel.reader.ui.DownloadsViewModel

/**
 * Download manager: the live queue on top, chapters already stored offline
 * below. Queue rows disappear as they finish, so what remains is always work
 * still outstanding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    factory: ViewModelProvider.Factory,
    onOpenChapter: (Long) -> Unit,
) {
    val vm: DownloadsViewModel = viewModel(factory = factory)
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()

    val inSelectionMode = selection.isNotEmpty()
    BackHandler(enabled = inSelectionMode) { vm.clearSelection() }

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
                },
            )
        } else {
            TopAppBar(
                title = { Text("Downloads (${downloaded.size})") },
                actions = {
                    if (queue.isNotEmpty()) {
                        TextButton(onClick = vm::cancelAll) { Text("Cancel all") }
                    }
                },
            )
        }

        if (downloaded.isEmpty() && queue.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    "Nothing downloaded.\nUse the download button on a chapter to save it offline.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                if (queue.isNotEmpty()) {
                    item {
                        Text(
                            "Queue (${queue.size})",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    items(queue, key = { "queued-${it.chapterId}" }) { entry ->
                        QueueRow(
                            entry = entry,
                            onCancel = { vm.cancel(entry.chapterId) },
                            onRetry = { vm.retry(entry.chapterId) },
                        )
                    }
                }

                item {
                    Text(
                        "Saved offline",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                items(downloaded, key = { it.chapterId }) { item ->
                    DownloadedRow(
                        novelTitle = item.novelTitle,
                        chapterName = item.name,
                        selected = item.chapterId in selection,
                        onClick = {
                            if (inSelectionMode) vm.toggleSelection(item.chapterId) else onOpenChapter(item.chapterId)
                        },
                        onLongClick = { vm.toggleSelection(item.chapterId) },
                        onDelete = { vm.delete(item.chapterId) },
                    )
                }
            }
        }

        if (inSelectionMode) {
            BottomAppBar {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = vm::deleteSelected) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete selected downloads")
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(entry: QueuedDownload, onCancel: () -> Unit, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.novelTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.chapterName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Text(
                when (entry.state) {
                    QueueState.QUEUED -> "Queued"
                    QueueState.DOWNLOADING -> "Downloading…"
                    QueueState.DONE -> "Done"
                    QueueState.FAILED -> "Failed"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (entry.state == QueueState.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        if (entry.state == QueueState.DOWNLOADING) {
            CircularProgressIndicator(Modifier.size(20.dp))
        }
        if (entry.state == QueueState.FAILED) {
            IconButton(onClick = onRetry) {
                Icon(Icons.Filled.Refresh, contentDescription = "Retry")
            }
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel download")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadedRow(
    novelTitle: String,
    chapterName: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                novelTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                chapterName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete download")
        }
    }
}
