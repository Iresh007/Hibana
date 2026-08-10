package com.opennovel.reader.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.opennovel.reader.ui.UpcomingRelease
import com.opennovel.reader.ui.UpcomingViewModel
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * When each library entry is expected to publish next, grouped by day.
 *
 * Sources publish no schedule, so every date here is inferred from the entry's
 * own release cadence. Entries that release erratically are marked as rough
 * rather than hidden — knowing something is "about weekly, irregular" is more
 * useful than an empty row, but presenting it as a fact would be a lie.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpcomingScreen(
    factory: ViewModelProvider.Factory,
    onBack: () -> Unit,
    onOpenNovel: (Long) -> Unit,
) {
    val vm: UpcomingViewModel = viewModel(factory = factory)
    val days by vm.byDay.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Expected releases") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (days.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text(
                    "Nothing to predict yet.\n\nA few chapters have to arrive before a " +
                        "release pattern can be worked out for an entry.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                days.forEach { (day, releases) ->
                    item(key = "d-$day") {
                        Text(
                            dayLabel(day),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(releases, key = { it.novelId }) { release ->
                        UpcomingRow(release) { onOpenNovel(release.novelId) }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpcomingRow(release: UpcomingRelease, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.size(width = 40.dp, height = 56.dp).clip(RoundedCornerShape(6.dp)),
        ) {
            if (release.coverUrl != null) {
                AsyncImage(
                    model = release.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                release.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(cadenceLabel(release.intervalDays))
                    if (!release.confident) append(" · irregular, rough estimate")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

private fun cadenceLabel(days: Int): String = when {
    days <= 1 -> "About daily"
    days <= 3 -> "Every few days"
    days in 6..8 -> "About weekly"
    days in 13..16 -> "About fortnightly"
    days in 28..32 -> "About monthly"
    else -> "About every $days days"
}

private fun dayLabel(day: Long): String {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val dayMs = TimeUnit.DAYS.toMillis(1)
    return when (day) {
        today -> "Today"
        today + dayMs -> "Tomorrow"
        else -> DateFormat.getDateInstance(DateFormat.FULL).format(Date(day))
    }
}
