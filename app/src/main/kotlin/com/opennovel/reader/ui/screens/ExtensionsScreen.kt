package com.opennovel.reader.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.opennovel.reader.data.AppSection
import com.opennovel.reader.extension.Ecosystem
import com.opennovel.reader.extension.ExtensionInfo
import com.opennovel.reader.extension.RepoExtension
import com.opennovel.reader.ui.ExtensionsViewModel
import com.opennovel.reader.ui.SectionScopeViewModel

/**
 * Extension manager: one grouped list of what needs updating, what's installed,
 * and what the enabled repos offer, in that order.
 *
 * Updates lead because they are the only time-sensitive group; "Available" is
 * last because it is unbounded — repos can list hundreds of entries.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ExtensionsScreen(
    factory: ViewModelProvider.Factory,
    onOpenRepos: () -> Unit = {},
    onBrowseSource: (Long) -> Unit = {},
    onOpenExtensionInfo: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val vm: ExtensionsViewModel = viewModel(factory = factory)
    val sectionVm: SectionScopeViewModel =
        viewModel(factory = remember(context) { SectionScopeViewModel.factory(context) })
    val section by sectionVm.section.collectAsStateWithLifecycle()
    val allInstalled by vm.installed.collectAsStateWithLifecycle()
    val allCatalogue by vm.catalogue.collectAsStateWithLifecycle()
    val allUpdatable by vm.updatable.collectAsStateWithLifecycle()
    val languages by vm.languages.collectAsStateWithLifecycle()
    val enabledLanguages by vm.enabledLanguages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()

    var showLanguages by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshInstalled(); vm.refreshCatalogue() }

    // An extension can only ever serve one section, so showing the other
    // section's packages here would offer installs that this half of the app
    // could never use.
    val installed = remember(allInstalled, section) {
        allInstalled.filter { AppSection.of(it.ecosystem) == section }
    }
    val catalogue = remember(allCatalogue, section) {
        allCatalogue.filter { AppSection.of(it.ecosystem) == section }
    }
    val updatable = remember(allUpdatable, section) {
        allUpdatable.filter { AppSection.of(it.ecosystem) == section }
    }

    // The catalogue is already language-filtered by the ViewModel; installed
    // extensions are filtered here so one toggle governs the whole screen.
    val installedShown = if (enabledLanguages.isEmpty()) {
        installed
    } else {
        installed.filter { it.lang in enabledLanguages || it.lang == "all" }
    }
    val available = catalogue.filterNot { it.installed }

    // Repo icons are keyed by package so an installed row can borrow the index
    // icon when the package itself has none (LNReader plugins aren't packages).
    val iconUrls = remember(catalogue) { catalogue.associate { it.pkgId to it.iconUrl } }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Extensions")
                    // The list is scoped, so the bar has to say what it is
                    // scoped to; otherwise a missing extension looks like a bug.
                    Text(
                        section.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            },
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

                installedShown.isEmpty() && catalogue.isEmpty() -> EmptyExtensions(
                    "No ${section.label} extensions yet.\n" +
                        "Add an extension store, then install from Available.",
                )

                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (updatable.isNotEmpty()) {
                        stickyHeader(key = "h-updates") {
                            SectionHeader(
                                title = "Update available (${updatable.size})",
                                action = "Update all",
                                onAction = { updatable.forEach(vm::installFromCatalogue) },
                            )
                        }
                        items(updatable, key = { "u/" + it.repoUrl + "/" + it.pkgId }) { item ->
                            RepoExtensionRow(
                                item = item,
                                actionLabel = "Update",
                                onAction = { vm.installFromCatalogue(item) },
                            )
                        }
                    }

                    stickyHeader(key = "h-installed") {
                        SectionHeader("Installed (${installedShown.size})")
                    }
                    if (installedShown.isEmpty()) {
                        item(key = "installed-empty") {
                            SectionNote("No ${section.label} extensions installed yet.")
                        }
                    }
                    items(installedShown, key = { it.ecosystem.name + "/" + it.pkgId }) { info ->
                        InstalledExtensionRow(
                            info = info,
                            iconUrl = iconUrls[info.pkgId],
                            onClick = { onOpenExtensionInfo(info.pkgId) },
                            // Long-press still jumps straight into the source's
                            // catalogue, which is the fast path for daily use.
                            onLongClick = {
                                if (info.trusted) vm.sourceIdsFor(info).firstOrNull()?.let(onBrowseSource)
                            },
                            onTrust = { vm.trust(info) },
                            onUntrust = { vm.untrust(info) },
                        )
                    }

                    stickyHeader(key = "h-available") {
                        SectionHeader("Available (${available.size})")
                    }
                    if (available.isEmpty()) {
                        item(key = "available-empty") {
                            SectionNote(
                                "No ${section.label} extensions available. " +
                                    "Check your extension stores, or clear the language filter.",
                            )
                        }
                    }
                    items(available, key = { "a/" + it.repoUrl + "/" + it.pkgId }) { item ->
                        RepoExtensionRow(
                            item = item,
                            actionLabel = "Install",
                            onAction = { vm.installFromCatalogue(item) },
                        )
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

/** Opaque background so list rows don't bleed through while the header is pinned. */
@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun SectionNote(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
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

/**
 * Extension icon: the installed package's launcher icon when the package is on
 * device, otherwise the repo index icon, otherwise a generic glyph.
 */
@Composable
internal fun ExtensionIcon(pkgId: String, iconUrl: String?, size: Int = 40) {
    val context = LocalContext.current
    val packageIcon = remember(pkgId) {
        runCatching { context.packageManager.getApplicationIcon(pkgId) }.getOrNull()
    }
    val model: Any? = packageIcon ?: iconUrl
    val modifier = Modifier.size(size.dp).clip(RoundedCornerShape(8.dp))
    if (model == null) {
        Icon(
            Icons.Filled.Extension,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = modifier,
        )
    } else {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier,
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
private fun InstalledExtensionRow(
    info: ExtensionInfo,
    iconUrl: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onTrust: () -> Unit,
    onUntrust: () -> Unit,
) {
    var showUntrust by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExtensionIcon(info.pkgId, iconUrl)
        Spacer(Modifier.width(12.dp))
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
            if (!info.trusted) {
                Text(
                    "Not trusted — its sources stay hidden until you trust it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (info.trusted) {
            IconButton(onClick = { showUntrust = true }) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Trusted — tap to remove trust",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            TextButton(onClick = onTrust) { Text("Trust") }
        }
    }

    if (showUntrust) {
        AlertDialog(
            onDismissRequest = { showUntrust = false },
            title = { Text("Remove trust?") },
            text = {
                Text(
                    "${info.name} will stop loading and its sources will be removed. " +
                        "You can trust it again at any time.",
                )
            },
            confirmButton = {
                TextButton(onClick = { onUntrust(); showUntrust = false }) { Text("Remove trust") }
            },
            dismissButton = { TextButton(onClick = { showUntrust = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RepoExtensionRow(
    item: RepoExtension,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExtensionIcon(item.pkgId, item.iconUrl)
        Spacer(Modifier.width(12.dp))
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
                    append("${item.ecosystem.label} · ${item.lang} · ")
                    // On an update the jump matters more than the target version alone.
                    if (item.hasUpdate) append("v${item.installedVersion} → v${item.version}")
                    else append("v${item.version}")
                    if (item.ecosystem != Ecosystem.LNREADER) append(" · APK")
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (item.hasUpdate) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                },
            )
        }
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}
