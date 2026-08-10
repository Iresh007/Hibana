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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.opennovel.reader.ui.BrowseSource
import com.opennovel.reader.ui.SourceListViewModel

/**
 * Mihon's Sources tab: every installed source as an icon + name + language row,
 * pinned favourites first, then grouped by language.
 *
 * Browsing and the WebView are hosted inside this screen rather than routed,
 * because the source list is the only place that knows which source a WebView
 * was opened for.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowseScreen(
    factory: ViewModelProvider.Factory,
    onOpenNovel: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val vm: SourceListViewModel = viewModel(factory = SourceListViewModel.factory(context))
    val sources by vm.sources.collectAsStateWithLifecycle()
    val section by vm.section.collectAsStateWithLifecycle()

    var openSource by remember { mutableStateOf<Pair<Long, Boolean>?>(null) }
    var webViewSource by remember { mutableStateOf<BrowseSource?>(null) }
    // Hosted here for the same reason as the browse/WebView screens: RootNav has
    // no destination for it, and this screen is what knows the source id.
    var preferencesSourceId by remember { mutableStateOf<Long?>(null) }

    preferencesSourceId?.let { id ->
        SourcePreferencesScreen(sourceId = id, onBack = { preferencesSourceId = null })
        return
    }

    webViewSource?.let { source ->
        SourceWebViewScreen(
            url = source.baseUrl,
            title = source.name,
            onBack = { webViewSource = null },
            onOpenInBrowser = vm::openInBrowser,
            onShare = { vm.share(it, source.name) },
        )
        return
    }

    openSource?.let { (sourceId, latest) ->
        SourceBrowseScreen(
            sourceId = sourceId,
            factory = factory,
            onOpenNovel = onOpenNovel,
            onBack = { openSource = null },
            initialLatest = latest,
        )
        return
    }

    if (sources.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(
                // Named per section so an empty list reads as "none of this
                // kind" rather than "the app found nothing at all" — the other
                // section may well be full.
                "No ${section.label} sources yet.\n" +
                    "Install a ${section.label} extension from the Extensions tab.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }

    val pinned = sources.filter { it.pinned }
    val byLanguage = sources.groupBy { it.lang }.toSortedMap()

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        if (pinned.isNotEmpty()) {
            stickyHeader(key = "h-pinned") { SourceGroupHeader("Pinned") }
            items(pinned, key = { "p/${it.id}" }) { source ->
                SourceRow(
                    source = source,
                    onBrowse = { openSource = source.id to false },
                    onLatest = { openSource = source.id to true },
                    onTogglePin = { vm.togglePin(source.id) },
                    onWebView = { webViewSource = source },
                    onOpenInBrowser = { vm.openInBrowser(source.baseUrl) },
                    onShare = { vm.share(source.baseUrl, source.name) },
                    onClearCookies = { vm.clearCookies(source.baseUrl) },
                    onSettings = { preferencesSourceId = source.id },
                )
            }
        }

        byLanguage.forEach { (lang, group) ->
            stickyHeader(key = "h-$lang") { SourceGroupHeader(languageLabel(lang)) }
            items(group, key = { "s/${it.id}" }) { source ->
                SourceRow(
                    source = source,
                    onBrowse = { openSource = source.id to false },
                    onLatest = { openSource = source.id to true },
                    onTogglePin = { vm.togglePin(source.id) },
                    onWebView = { webViewSource = source },
                    onOpenInBrowser = { vm.openInBrowser(source.baseUrl) },
                    onShare = { vm.share(source.baseUrl, source.name) },
                    onClearCookies = { vm.clearCookies(source.baseUrl) },
                    onSettings = { preferencesSourceId = source.id },
                )
            }
        }
    }
}

/** Opaque background so list rows don't bleed through while the header is pinned. */
@Composable
private fun SourceGroupHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceRow(
    source: BrowseSource,
    onBrowse: () -> Unit,
    onLatest: () -> Unit,
    onTogglePin: () -> Unit,
    onWebView: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onShare: () -> Unit,
    onClearCookies: () -> Unit,
    onSettings: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onBrowse, onLongClick = { menuOpen = true })
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExtensionIcon(source.pkgId, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    source.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    languageLabel(source.lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            if (source.pinned) {
                Icon(
                    Icons.Filled.PushPin,
                    contentDescription = "Pinned",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            // Only offered where the source has a real latest feed; otherwise it
            // would just be a second button onto the popular listing.
            if (source.supportsLatest) {
                TextButton(onClick = onLatest) { Text("Latest") }
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            // First entry because an unconfigured source (no mirror set) looks
            // broken until this is used, so it is the most likely reason to be
            // in this menu at all.
            if (source.hasPreferences) {
                DropdownMenuItem(
                    text = { Text("Settings") },
                    onClick = { menuOpen = false; onSettings() },
                )
            }
            DropdownMenuItem(
                text = { Text(if (source.pinned) "Unpin" else "Pin") },
                onClick = { menuOpen = false; onTogglePin() },
            )
            DropdownMenuItem(
                text = { Text("Open in WebView") },
                onClick = { menuOpen = false; onWebView() },
            )
            DropdownMenuItem(
                text = { Text("Open in browser") },
                onClick = { menuOpen = false; onOpenInBrowser() },
            )
            DropdownMenuItem(
                text = { Text("Share") },
                onClick = { menuOpen = false; onShare() },
            )
            DropdownMenuItem(
                text = { Text("Clear cookies") },
                onClick = { menuOpen = false; onClearCookies() },
            )
        }
    }
}

/**
 * Language names for the codes extensions actually ship with. Unknown codes fall
 * back to the raw code rather than being hidden, since a wrong-looking header is
 * far easier to diagnose than a missing group.
 */
internal fun languageLabel(code: String): String = when (code.lowercase()) {
    "all" -> "All languages"
    "en" -> "English"
    "ja" -> "Japanese"
    "ko" -> "Korean"
    "zh" -> "Chinese"
    "es" -> "Spanish"
    "fr" -> "French"
    "de" -> "German"
    "pt", "pt-br" -> "Portuguese"
    "ru" -> "Russian"
    "it" -> "Italian"
    "id" -> "Indonesian"
    "ar" -> "Arabic"
    "tr" -> "Turkish"
    "th" -> "Thai"
    "vi" -> "Vietnamese"
    "pl" -> "Polish"
    else -> code.uppercase()
}
