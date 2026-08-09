package com.opennovel.reader.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opennovel.reader.ui.BrowseViewModel

/**
 * Migrates every library entry belonging to one source.
 *
 * Resolves the source's entries, then hands off to [MigrationScreen] so the
 * preview-and-confirm flow is identical whether migration started from a source
 * or from a library selection — one code path, one set of behaviours.
 */
@Composable
fun MigrateSourceScreen(
    sourceId: Long,
    factory: ViewModelProvider.Factory,
    onBack: () -> Unit,
) {
    val vm: BrowseViewModel = viewModel(factory = factory)
    var novelIds by remember { mutableStateOf<List<Long>?>(null) }

    LaunchedEffect(sourceId) { vm.novelIdsForSource(sourceId) { novelIds = it } }

    when (val ids = novelIds) {
        null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        else -> if (ids.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    "Nothing in your library uses this source.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        } else {
            MigrationScreen(novelIds = ids, factory = factory, onBack = onBack)
        }
    }
}
