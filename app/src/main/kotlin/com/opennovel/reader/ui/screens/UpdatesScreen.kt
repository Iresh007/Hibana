package com.opennovel.reader.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.opennovel.reader.data.db.ChapterWithNovel
import com.opennovel.reader.ui.UpdatesViewModel

/**
 * Newest chapters across the library, mirroring Mihon's Updates tab. Refresh
 * sweeps every library novel's source; progress is shown because a large library
 * takes a while and a silent spinner looks hung.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(
    factory: ViewModelProvider.Factory,
    onOpenChapter: (Long) -> Unit,
) {
    val vm: UpdatesViewModel = viewModel(factory = factory)
    val updates by vm.updates.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(progress?.let { "Updates · $it" } ?: "Updates") },
            actions = {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.padding(end = 16.dp).width(24.dp))
                } else {
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Check for new chapters")
                    }
                }
            },
        )

        if (updates.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    "No updates yet.\nAdd novels to your library, then refresh.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn {
                items(updates, key = { it.chapterId }) { item ->
                    UpdateRow(
                        item = item,
                        onOpen = { onOpenChapter(item.chapterId) },
                        onDownload = { vm.download(item.chapterId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateRow(
    item: ChapterWithNovel,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.width(40.dp).aspectRatioCover().clip(RoundedCornerShape(6.dp)),
        ) {
            if (item.coverUrl != null) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                item.novelTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Read chapters fade back so genuinely new ones stand out.
                color = if (item.read) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                item.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        IconButton(onClick = onDownload, enabled = !item.downloaded) {
            Icon(
                if (item.downloaded) Icons.Filled.DownloadDone else Icons.Filled.Download,
                contentDescription = if (item.downloaded) "Downloaded" else "Download",
            )
        }
    }
}
