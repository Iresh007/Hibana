package com.opennovel.reader.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.opennovel.reader.source.model.SNovel
import com.opennovel.reader.ui.BrowseViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(factory: ViewModelProvider.Factory) {
    val vm: BrowseViewModel = viewModel(factory = factory)
    val sources by vm.sources.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val added = remember { mutableStateMapOf<String, Boolean>() }

    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.loadPopular() }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text(
            "Browse",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        // Source selector
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sources.forEach { src ->
                FilterChip(
                    selected = vm.activeSourceId == src.id,
                    onClick = { vm.selectSource(src.id) },
                    label = { Text(src.name) },
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search this source") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        LaunchedEffect(query) {
            if (query.length >= 2) vm.search(query) else if (query.isEmpty()) vm.loadPopular()
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results, key = { it.url }) { novel ->
                    ResultRow(
                        novel = novel,
                        added = added[novel.url] == true,
                        onAdd = {
                            scope.launch {
                                vm.addToLibrary(novel)
                                added[novel.url] = true
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultRow(novel: SNovel, added: Boolean, onAdd: () -> Unit) {
    Surface(tonalElevation = 1.dp, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.width(52.dp).aspectRatioCover().clip(RoundedCornerShape(8.dp)),
            ) {
                if (novel.coverUrl != null) {
                    AsyncImage(
                        model = novel.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    novel.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (novel.author != null) {
                    Text(
                        novel.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onAdd, enabled = !added) {
                Icon(
                    if (added) Icons.Filled.Check else Icons.Filled.Add,
                    contentDescription = if (added) "Added to library" else "Add to library",
                )
            }
        }
    }
}
