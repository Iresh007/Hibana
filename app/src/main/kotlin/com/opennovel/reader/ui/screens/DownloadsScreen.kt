package com.opennovel.reader.ui.screens

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.opennovel.reader.download.DownloadState
import com.opennovel.reader.ui.DownloadsViewModel

/**
 * Download manager: chapters stored offline, plus anything currently running or
 * failed. Live state comes from the downloader, so a chapter shows a spinner
 * while fetching and a retry affordance if it failed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    factory: ViewModelProvider.Factory,
    onOpenChapter: (Long) -> Unit,
) {
    val vm: DownloadsViewModel = viewModel(factory = factory)
    val downloaded by vm.downloaded.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()

    // Chapters mid-flight aren't in the DB as downloaded yet, so surface them
    // from the downloader's live state instead.
    val active = progress.filterValues { it == DownloadState.RUNNING || it == DownloadState.FAILED }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Downloads (${downloaded.size})") })

        if (downloaded.isEmpty() && active.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    "Nothing downloaded.\nUse the download button on a chapter to save it offline.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn {
                if (active.isNotEmpty()) {
                    item {
                        Text(
                            "In progress",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    items(active.keys.toList(), key = { "active-$it" }) { chapterId ->
                        val state = active[chapterId]
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (state == DownloadState.FAILED) "Failed" else "Downloading…",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (state == DownloadState.FAILED) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            if (state == DownloadState.FAILED) {
                                IconButton(onClick = { vm.retry(chapterId) }) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Retry")
                                }
                            } else {
                                CircularProgressIndicator(Modifier.size(20.dp))
                            }
                        }
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
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.novelTitle,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                item.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { vm.delete(item.chapterId) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete download")
                        }
                    }
                }
            }
        }
    }
}
