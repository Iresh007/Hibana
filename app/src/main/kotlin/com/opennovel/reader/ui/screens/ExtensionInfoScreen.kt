package com.opennovel.reader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opennovel.reader.ui.ExtensionInfoViewModel
import com.opennovel.reader.ui.ExtensionsViewModel
import com.opennovel.reader.ui.extensionInfoViewModelFactory

/**
 * Everything about one installed extension: identity, the trust decision, and
 * the sources it contributes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionInfoScreen(
    packageName: String,
    factory: ViewModelProvider.Factory,
    onBack: () -> Unit,
    onBrowseSource: (Long) -> Unit,
) {
    val context = LocalContext.current
    val infoVm: ExtensionInfoViewModel = viewModel(
        factory = extensionInfoViewModelFactory(context, packageName),
    )
    // Trust/untrust lives on ExtensionsViewModel because approving must also
    // load the extension right away, which needs the loader list.
    val extensionsVm: ExtensionsViewModel = viewModel(factory = factory)

    val state by infoVm.state.collectAsStateWithLifecycle()
    val info = state.info

    var confirmUntrust by remember { mutableStateOf(false) }
    // Source preferences are a full screen, but this destination isn't in
    // RootNav, so it is hosted here and swaps out the content instead.
    var preferencesSourceId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(packageName) { infoVm.refresh() }

    preferencesSourceId?.let { id ->
        SourcePreferencesScreen(sourceId = id, onBack = { preferencesSourceId = null })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(info?.name ?: "Extension", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (info != null && infoVm.isPackage) {
                        IconButton(onClick = infoVm::uninstall) {
                            Icon(Icons.Filled.Delete, contentDescription = "Uninstall")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (info == null) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text(
                    "This extension is no longer installed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExtensionIcon(info.pkgId, iconUrl = null, size = 56)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(info.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        info.pkgId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Text(
                        "${info.ecosystem.label} · v${info.versionName} · ${languageSummary(info.lang, state.sources.map { it.lang })}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            HorizontalDivider()

            /*
             * Trust is keyed on package id *plus* signing certificate hash (see
             * ExtensionTrustStore): an update re-signed by a different party is a
             * different key, so it silently loses trust and must be re-approved.
             * Approving the package alone would let a hijacked or impersonated
             * rebuild inherit the user's earlier decision.
             */
            ToggleRow(
                title = if (info.trusted) "Trusted" else "Not trusted",
                subtitle = if (info.trusted) {
                    "Approved for this signing certificate. A re-signed update needs approval again."
                } else {
                    "Its sources stay hidden until you trust it."
                },
                checked = info.trusted,
                onCheckedChange = { on ->
                    if (on) extensionsVm.trust(info) else confirmUntrust = true
                },
                emphasise = !info.trusted,
            )

            ToggleRow(
                title = "Enabled",
                subtitle = "Disabling removes this extension's sources without uninstalling it.",
                checked = state.enabled,
                onCheckedChange = infoVm::setEnabled,
                enabled = info.trusted,
            )

            ToggleRow(
                title = "Incognito mode",
                subtitle = "Don't record reading history for this extension's sources.",
                checked = state.incognito,
                onCheckedChange = infoVm::setIncognito,
            )

            HorizontalDivider()

            val website = state.sources.firstOrNull { it.baseUrl.isNotBlank() }?.baseUrl
            ActionRow(
                title = "Open source website",
                subtitle = website ?: "This extension didn't report a website",
                enabled = website != null,
                onClick = { website?.let(infoVm::openInBrowser) },
                icon = { Icon(Icons.Filled.OpenInBrowser, contentDescription = null) },
            )

            if (infoVm.isPackage) {
                ActionRow(
                    title = "Uninstall",
                    subtitle = "Hands the package to Android's uninstaller",
                    onClick = infoVm::uninstall,
                    icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                )
            }

            if (info.trusted) {
                ActionRow(
                    title = "Untrust",
                    subtitle = "Stop running this extension's code and remove its sources",
                    onClick = { confirmUntrust = true },
                    destructive = true,
                )
            }

            HorizontalDivider()

            Text(
                "Sources (${state.sources.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            if (state.sources.isEmpty()) {
                Text(
                    if (info.trusted) {
                        "This extension didn't register any sources."
                    } else {
                        "Trust this extension to load its sources."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            state.sources.forEach { source ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .clickableMinTouch { onBrowseSource(source.id) }
                            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
                    ) {
                        Text(source.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull(
                                source.lang.takeIf { it.isNotBlank() },
                                source.baseUrl.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Only shown where the extension actually declares settings;
                    // an always-present gear that opens an empty screen is worse
                    // than none at all.
                    if (source.hasPreferences) {
                        IconButton(
                            onClick = { preferencesSourceId = source.id },
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Icon(Icons.Filled.Settings, contentDescription = "Source settings")
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmUntrust && info != null) {
        AlertDialog(
            onDismissRequest = { confirmUntrust = false },
            title = { Text("Remove trust?") },
            text = {
                Text(
                    "${info.name} will stop loading and its sources will be removed. " +
                        "You can trust it again at any time.",
                )
            },
            confirmButton = {
                TextButton(onClick = { extensionsVm.untrust(info); confirmUntrust = false }) {
                    Text("Remove trust")
                }
            },
            dismissButton = { TextButton(onClick = { confirmUntrust = false }) { Text("Cancel") } },
        )
    }
}

/** Extensions declare one language; their sources may span several. */
private fun languageSummary(extensionLang: String, sourceLangs: List<String>): String {
    val langs = (sourceLangs + extensionLang).filter { it.isNotBlank() }.distinct()
    return if (langs.isEmpty()) extensionLang else langs.joinToString(", ")
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    emphasise: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickableMinTouch { if (enabled) onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (emphasise) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickableMinTouch { if (enabled) onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    destructive -> MaterialTheme.colorScheme.error
                    enabled -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                },
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
