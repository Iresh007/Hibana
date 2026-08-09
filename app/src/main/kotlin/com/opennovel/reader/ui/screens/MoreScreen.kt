package com.opennovel.reader.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.opennovel.reader.R

/**
 * Overflow hub, matching Mihon's "More" tab: keeps the bottom bar to five items
 * while still reaching the less-frequent destinations.
 *
 * [factory] is accepted for call-site consistency with the other tabs; nothing
 * on this screen needs a ViewModel yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER")
@Composable
fun MoreScreen(
    factory: ViewModelProvider.Factory,
    onOpenExtensions: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSettingsSection: (String) -> Unit,
    onOpenStats: () -> Unit,
) {
    // Session-scoped only: no preference backs either switch yet, so they are
    // deliberately not persisted rather than silently written somewhere that
    // nothing reads. See the integration note in the pull request.
    var downloadedOnly by rememberSaveable { mutableStateOf(false) }
    var incognito by rememberSaveable { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopAppBar(title = { Text(stringResource(R.string.app_name)) })

        Text(
            stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        HorizontalDivider()

        MoreToggle(
            icon = Icons.Filled.CloudOff,
            title = "Downloaded only",
            subtitle = "Hide anything not saved for offline reading",
            checked = downloadedOnly,
            onCheckedChange = { downloadedOnly = it },
        )
        MoreToggle(
            icon = Icons.Filled.VisibilityOff,
            title = "Incognito mode",
            subtitle = "Pause history and reading progress",
            checked = incognito,
            onCheckedChange = { incognito = it },
        )

        HorizontalDivider()

        MoreRow(
            Icons.Filled.Download,
            "Downloads",
            "Chapters saved for offline reading",
            onOpenDownloads,
        )
        MoreRow(
            Icons.Filled.Category,
            "Categories",
            "Organise the library into shelves",
        ) { onOpenSettingsSection("library") }
        MoreRow(
            Icons.Filled.QueryStats,
            "Statistics",
            "What your library holds and how much you've read",
            onOpenStats,
        )
        MoreRow(
            Icons.Filled.Extension,
            "Extensions",
            "Install and manage sources",
            onOpenExtensions,
        )
        MoreRow(
            Icons.Filled.Settings,
            "Settings",
            "Reader, narration, translation, backup",
            onOpenSettings,
        )
        MoreRow(
            Icons.Filled.Info,
            "About",
            "Version, licences and links",
        ) { showAbout = true }
    }

    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreToggle(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable { onCheckedChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Read at runtime because buildConfig generation is off, so there is no
    // BuildConfig.VERSION_NAME to reference.
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        icon = { Icon(Icons.Filled.Info, contentDescription = null) },
        title = { Text(stringResource(R.string.app_name_full)) },
        text = {
            Column {
                Text(stringResource(R.string.app_subtitle), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Version $version",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Open source, ad-free, and built on the Mihon/Tachiyomi extension ecosystem.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                AboutLink("Mihon", "https://mihon.app", { link -> context.openLink(link) })
                AboutLink("Tachiyomi extensions", "https://github.com/keiyoushi/extensions", { link -> context.openLink(link) })
                AboutLink("LNReader plugins", "https://github.com/LNReader/lnreader-plugins", { link -> context.openLink(link) })
            }
        },
    )
}

@Composable
private fun AboutLink(label: String, url: String, onOpen: (String) -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(url) }
            .padding(vertical = 6.dp),
    )
}

/** Fails silently: a device with no browser shouldn't crash the About dialog. */
private fun android.content.Context.openLink(url: String) {
    runCatching {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
