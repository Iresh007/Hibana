package com.opennovel.reader.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.opennovel.reader.data.ChapterGap
import com.opennovel.reader.data.findChapterGaps
import com.opennovel.reader.data.db.ChapterEntity
import com.opennovel.reader.data.db.NovelEntity
import com.opennovel.reader.ui.NovelDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelDetailScreen(
    novelId: Long,
    factory: ViewModelProvider.Factory,
    onOpenChapter: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val vm: NovelDetailViewModel = viewModel(factory = factory)
    LaunchedEffect(novelId) { vm.load(novelId) }

    val novel by vm.novel.collectAsStateWithLifecycle()
    val chapters by vm.chapters.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()

    // Recomputed only when the chapter list changes, not on every recomposition.
    val gaps = remember(chapters) { findChapterGaps(chapters) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(novel?.title ?: "Details", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = vm::refresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh chapters")
                }
            },
        )

        LazyColumn(Modifier.fillMaxSize()) {
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
                    onOpen = { onOpenChapter(chapter.id) },
                    onToggleRead = { vm.markRead(chapter.id, !chapter.read) },
                    onDownload = { vm.download(chapter.id) },
                )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(
    chapter: ChapterEntity,
    onOpen: () -> Unit,
    onToggleRead: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .combinedClickable(onClick = onOpen, onLongClick = onToggleRead)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
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
                if (chapter.downloaded) add("Downloaded")
            }
            if (meta.isNotEmpty()) {
                Text(
                    meta.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
        IconButton(onClick = onDownload, enabled = !chapter.downloaded) {
            Icon(
                if (chapter.downloaded) Icons.Filled.DownloadDone else Icons.Filled.Download,
                contentDescription = if (chapter.downloaded) "Downloaded" else "Download chapter",
            )
        }
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

