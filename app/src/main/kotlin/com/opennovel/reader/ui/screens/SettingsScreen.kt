package com.opennovel.reader.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider

/**
 * The settings categories, mirroring Mihon's tree.
 *
 * [id] is the navigation argument and is the single source of truth shared with
 * [SettingsSectionScreen], so the hub and the sub-screens cannot drift apart.
 * Ids are persisted in back-stack entries, so treat them as stable strings.
 */
enum class SettingsSection(
    val id: String,
    val title: String,
    val summary: String,
    val icon: ImageVector,
) {
    APPEARANCE("appearance", "Appearance", "Theme, colours, language", Icons.Filled.Palette),
    LIBRARY("library", "Library", "Categories, global updates, badges, layout", Icons.Filled.CollectionsBookmark),
    READER("reader", "Reader", "Comic layout and novel typography", Icons.Filled.MenuBook),
    TTS_TRANSLATION(
        "tts_translation",
        "Narration & translation",
        "Voice, text recognition, translation",
        Icons.AutoMirrored.Filled.VolumeUp,
    ),
    DOWNLOADS("downloads", "Downloads", "Location, automatic downloads, limits", Icons.Filled.Download),
    BROWSE("browse", "Browse", "Sources, NSFW, extension repositories", Icons.Filled.Explore),
    DATA_STORAGE("data_storage", "Data & storage", "Backups, cache, cookies", Icons.Filled.Storage),
    PRIVACY("privacy", "Privacy & security", "App lock, secure screen, incognito", Icons.Filled.Security),
    ADVANCED("advanced", "Advanced", "Maintenance, diagnostics, build info", Icons.Filled.Build);

    companion object {
        fun fromId(id: String): SettingsSection? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Settings hub. Only lists categories; every control lives in the sub-screen
 * that [onOpenSection] navigates to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    factory: ViewModelProvider.Factory,
    onBack: () -> Unit,
    onOpenSection: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            SettingsSection.entries.forEach { section ->
                SettingsCategoryRow(section) { onOpenSection(section.id) }
            }
        }
    }
}

@Composable
private fun SettingsCategoryRow(section: SettingsSection, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickableMinTouch(onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(section.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(section.title, style = MaterialTheme.typography.titleSmall)
            Text(
                section.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}
