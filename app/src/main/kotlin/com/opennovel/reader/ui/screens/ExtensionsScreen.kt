package com.opennovel.reader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opennovel.reader.extension.ExtensionInfo
import com.opennovel.reader.ui.ExtensionsViewModel

/**
 * Extension manager: what's installed across all four ecosystems, plus the
 * browsable LNReader plugin catalogue.
 *
 * APK-based ecosystems (Mihon/Manatan/IReader) are installed through the system
 * package manager, so they are listed but not installable from here — the
 * "Available" tab covers LNReader JS plugins, which Hibana can fetch directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(factory: ViewModelProvider.Factory) {
    val vm: ExtensionsViewModel = viewModel(factory = factory)
    val installed by vm.installed.collectAsStateWithLifecycle()
    val available by vm.available.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()

    var tab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { vm.refreshInstalled() }
    LaunchedEffect(tab) { if (tab == 1 && available.isEmpty()) vm.browseRepository() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Extensions") },
            actions = {
                IconButton(onClick = { if (tab == 0) vm.refreshInstalled() else vm.browseRepository() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            },
        )

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Installed (${installed.size})") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Available") })
        }

        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        Box(Modifier.fillMaxSize()) {
            val list = if (tab == 0) installed else available
            when {
                busy && list.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

                list.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        if (tab == 0) {
                            "No extensions installed.\nInstall Mihon, Manatan or IReader extension APKs, " +
                                "or add LNReader plugins from Available."
                        } else {
                            "No plugins found."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(32.dp),
                    )
                }

                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(list, key = { it.ecosystem.name + "/" + it.pkgId }) { info ->
                        ExtensionRow(
                            info = info,
                            showInstall = tab == 1 && !info.installed,
                            onInstall = { vm.install(info) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionRow(
    info: ExtensionInfo,
    showInstall: Boolean,
    onInstall: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                info.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${info.ecosystem.label} · ${info.lang} · v${info.versionName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        when {
            showInstall -> IconButton(onClick = onInstall) {
                Icon(Icons.Filled.Download, contentDescription = "Install ${info.name}")
            }
            info.installed -> Icon(
                Icons.Filled.Check,
                contentDescription = "Installed",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
