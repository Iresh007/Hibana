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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.opennovel.reader.data.db.ChapterWithNovel
import com.opennovel.reader.ui.UpdatesViewModel
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Chapters that arrived since each entry joined the library, grouped by the day
 * they showed up — Mihon's Updates tab.
 *
 * Grouping is by fetch day rather than upload day because a large share of
 * sources publish no upload date; grouping on that put everything under a single
 * 1970 heading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(
    factory: ViewModelProvider.Factory,
    onOpenChapter: (Long) -> Unit,
    onOpenUpcoming: () -> Unit = {},
) {
    val vm: UpdatesViewModel = viewModel(factory = factory)
    val updates by vm.updates.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val lastRefresh by vm.lastRefresh.collectAsStateWithLifecycle()

    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbars.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    // Bucket into day headings once per data change rather than per row.
    val sections = remember(updates) { groupByDay(updates) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(progress?.let { "Updates · $it" } ?: "Updates")
                        Text(
                            lastRefreshLabel(lastRefresh),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenUpcoming) {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = "Expected releases")
                    }
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.padding(end = 16.dp).width(24.dp))
                    } else {
                        IconButton(onClick = vm::refresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Check for new chapters")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (sections.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text(
                    "No new chapters.\n\nUpdates lists chapters that appear after an entry " +
                        "joins your library, so this stays empty until something new releases.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                sections.forEach { (heading, rows) ->
                    item(key = "h-$heading") { DayHeading(heading) }
                    items(rows, key = { it.chapterId }) { item ->
                        UpdateRow(
                            item = item,
                            onOpen = { onOpenChapter(item.chapterId) },
                            onDownload = { vm.download(item.chapterId) },
                            onBookmark = { vm.toggleBookmark(item.chapterId, !item.bookmark) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun UpdateRow(
    item: ChapterWithNovel,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onBookmark: () -> Unit,
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
        IconButton(onClick = onBookmark) {
            Icon(
                if (item.bookmark) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                contentDescription = if (item.bookmark) "Remove bookmark" else "Bookmark",
                tint = if (item.bookmark) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                },
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

/** Buckets rows under Today / Yesterday / an absolute date, preserving order. */
private fun groupByDay(rows: List<ChapterWithNovel>): List<Pair<String, List<ChapterWithNovel>>> {
    if (rows.isEmpty()) return emptyList()
    val today = startOfDay(System.currentTimeMillis())
    val dayMs = TimeUnit.DAYS.toMillis(1)
    return rows.groupBy { startOfDay(it.dateFetch) }
        .toList()
        .sortedByDescending { it.first }
        .map { (day, items) ->
            val label = when (day) {
                today -> "Today"
                today - dayMs -> "Yesterday"
                else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                    .format(java.util.Date(day))
            }
            label to items
        }
}

private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun lastRefreshLabel(millis: Long): String {
    if (millis <= 0L) return "Never refreshed"
    val delta = System.currentTimeMillis() - millis
    val mins = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    val days = TimeUnit.MILLISECONDS.toDays(delta)
    return when {
        mins < 1 -> "Refreshed just now"
        mins < 60 -> "Refreshed ${mins}m ago"
        hours < 24 -> "Refreshed ${hours}h ago"
        else -> "Refreshed ${days}d ago"
    }
}
