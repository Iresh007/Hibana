package com.opennovel.reader.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Upgrade
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
fun ExtensionsScreen(
    factory: ViewModelProvider.Factory,
    onOpenRepos: () -> Unit = {},
    onBrowseSource: (Long) -> Unit = {},
) {
    val vm: ExtensionsViewModel = viewModel(factory = factory)
    val installed by vm.installed.collectAsStateWithLifecycle()
    val catalogue by vm.catalogue.collectAsStateWithLifecycle()
    val updatable by vm.updatable.collectAsStateWithLifecycle()
    val languages by vm.languages.collectAsStateWithLifecycle()
    val enabledLanguages by vm.enabledLanguages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()

    var tab by remember { mutableIntStateOf(0) }
    var showLanguages by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshInstalled(); vm.refreshCatalogue() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Extensions") },
            actions = {
                if (languages.isNotEmpty()) {
                    IconButton(onClick = { showLanguages = true }) {
                        Icon(
                            Icons.Filled.Translate,
                            contentDescription = "Filter by language",
                            tint = if (enabledLanguages.isNotEmpty()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            },
                        )
                    }
                }
                IconButton(onClick = onOpenRepos) {
                    Icon(Icons.Filled.Storefront, contentDescription = "Extension stores")
                }
                IconButton(onClick = { vm.refreshInstalled(); vm.refreshCatalogue() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            },
        )

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Installed (${installed.size})") })
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                // Update count is the reason to open this tab, so it goes in the label.
                text = {
                    Text(if (updatable.isEmpty()) "Available" else "Available (${updatable.size} update)")
                },
            )
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
            when {
                busy && installed.isEmpty() && catalogue.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

                tab == 0 && installed.isEmpty() -> EmptyExtensions(
                    "No extensions installed.\nAdd an extension store, then install from Available.",
                )

                tab == 1 && catalogue.isEmpty() -> EmptyExtensions(
                    "Nothing available.\nCheck your extension stores, or clear the language filter.",
                )

                tab == 0 -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                ) {
                    items(installed, key = { it.ecosystem.name + "/" + it.pkgId }) { info ->
                        InstalledExtensionRow(
                            info = info,
                            // Long-press opens the source's own catalogue, which
                            // is how you discover what an extension actually has.
                            onBrowse = { vm.sourceIdsFor(info).firstOrNull()?.let(onBrowseSource) },
                        )
                    }
                }

                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                ) {
                    items(catalogue, key = { it.repoUrl + "/" + it.pkgId }) { item ->
                        CatalogueExtensionRow(item = item, onInstall = { vm.installFromCatalogue(item) })
                    }
                }
            }
        }
    }

    if (showLanguages) {
        LanguageFilterDialog(
            languages = languages,
            enabled = enabledLanguages,
            onToggle = vm::toggleLanguage,
            onDismiss = { showLanguages = false },
        )
    }
}

@Composable
private fun EmptyExtensions(message: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(32.dp),
        )
    }
}

/** Language toggles; no selection means "show everything" rather than nothing. */
@Composable
private fun LanguageFilterDialog(
    languages: List<String>,
    enabled: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Languages") },
        text = {
            LazyColumn {
                items(languages, key = { it }) { lang ->
                    Row(
                        Modifier.fillMaxWidth().clickableMinTouch { onToggle(lang) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(checked = lang in enabled, onCheckedChange = { onToggle(lang) })
                        Text(lang, Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InstalledExtensionRow(info: ExtensionInfo, onBrowse: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onBrowse, onLongClick = onBrowse)
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
        Icon(
            Icons.Filled.Check,
            contentDescription = "Installed",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun CatalogueExtensionRow(
    item: com.opennovel.reader.extension.RepoExtension,
    onInstall: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.nsfw) {
                    Text(
                        "18+",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            Text(
                buildString {
                    append("${item.ecosystem.label} · ${item.lang} · v${item.version}")
                    if (item.hasUpdate) append(" → update available")
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (item.hasUpdate) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                },
            )
        }
        when {
            item.hasUpdate -> IconButton(onClick = onInstall) {
                Icon(Icons.Filled.Upgrade, contentDescription = "Update ${item.name}")
            }
            item.installed -> Icon(
                Icons.Filled.Check,
                contentDescription = "Installed",
                tint = MaterialTheme.colorScheme.primary,
            )
            else -> IconButton(onClick = onInstall) {
                Icon(Icons.Filled.Download, contentDescription = "Install ${item.name}")
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
