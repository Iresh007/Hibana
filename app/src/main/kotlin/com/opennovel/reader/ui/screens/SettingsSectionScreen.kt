package com.opennovel.reader.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.opennovel.reader.ui.MaintenanceViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opennovel.reader.data.AppSection
import com.opennovel.reader.data.AutoBackupFrequency
import com.opennovel.reader.data.LibraryDisplayMode
import com.opennovel.reader.data.NO_DEFAULT_CATEGORY
import com.opennovel.reader.data.OcrScriptSetting
import com.opennovel.reader.data.PageLayout
import com.opennovel.reader.data.ReadingMode
import com.opennovel.reader.data.SpeechLanguage
import com.opennovel.reader.data.ThemeMode
import com.opennovel.reader.data.TranslateLanguage
import com.opennovel.reader.data.UpdateSchedule
import com.opennovel.reader.ui.BackupViewModel
import com.opennovel.reader.ui.LibraryViewModel
import com.opennovel.reader.ui.SectionPrefsViewModel
import com.opennovel.reader.ui.SettingsSectionViewModel
import com.opennovel.reader.ui.SettingsViewModel
import com.opennovel.reader.ui.UpdatesViewModel

/**
 * One settings category, resolved from [sectionId] via [SettingsSection].
 *
 * [sectionId] names a *settings category* ("reader", "library"). It is unrelated
 * to [AppSection], the Manga/Novel half of the app; a category may edit
 * preferences for either app section, which is why the two are never held in the
 * same variable here.
 *
 * Globally-shared preferences with side effects (theme, TTS, update schedule)
 * stay on [SettingsViewModel] so WorkManager rescheduling keeps happening;
 * plain global preferences live on [SettingsSectionViewModel]; anything that
 * differs between comics and prose lives on [SectionPrefsViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSectionScreen(
    sectionId: String,
    factory: ViewModelProvider.Factory,
    onBack: () -> Unit,
    onOpenRepos: () -> Unit,
) {
    val section = SettingsSection.fromId(sectionId)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(section?.title ?: "Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (section) {
                SettingsSection.APPEARANCE -> AppearanceSection(factory)
                SettingsSection.LIBRARY -> LibrarySection(factory)
                SettingsSection.READER -> ReaderSection(factory)
                SettingsSection.TTS_TRANSLATION -> TtsTranslationSection(factory)
                SettingsSection.DOWNLOADS -> DownloadsSection(factory)
                SettingsSection.BROWSE -> BrowseSection(factory, onOpenRepos)
                SettingsSection.DATA_STORAGE -> DataStorageSection(factory)
                SettingsSection.PRIVACY -> PrivacySection(factory)
                SettingsSection.ADVANCED -> AdvancedSection(factory)
                // Reached only if a stale deep link names a category we removed.
                null -> Text("Unknown settings section \"$sectionId\".")
            }
        }
    }
}

// --- appearance ---------------------------------------------------------

@Composable
private fun AppearanceSection(factory: ViewModelProvider.Factory) {
    val vm: SettingsViewModel = viewModel(factory = factory)
    val extra: SettingsSectionViewModel =
        viewModel(factory = SettingsSectionViewModel.factory(LocalContext.current))
    val s by vm.settings.collectAsStateWithLifecycle()

    PrefSection("Theme") {
        ChipRow(
            options = ThemeMode.entries,
            selected = s.themeMode,
            label = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
            onSelect = vm::setTheme,
        )
        Hint("Sepia and black are reading-friendly variants of light and dark.")
    }

    PrefSection("Colour") {
        SwitchRow(
            title = "Dynamic colour",
            subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "Use the Material You palette from your wallpaper"
            } else {
                "Requires Android 12 or newer"
            },
            checked = s.dynamicColor,
            enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            onCheckedChange = extra::setDynamicColor,
        )
    }

    PrefSection("Language") {
        // Stored as a BCP-47 tag so it can be handed straight to
        // AppCompatDelegate/LocaleManager once localisation lands.
        ChipRow(
            options = listOf("" to "System default", "en" to "English", "hi" to "हिन्दी"),
            label = { it.second },
            isSelected = { it.first == s.appLanguage },
            onSelect = { extra.setAppLanguage(it.first) },
        )
        Hint("Only English strings ship today; other choices take effect as translations land.")
    }
}

// --- library ------------------------------------------------------------

@Composable
private fun LibrarySection(factory: ViewModelProvider.Factory) {
    val vm: SettingsViewModel = viewModel(factory = factory)
    val libraryVm: LibraryViewModel = viewModel(factory = factory)
    val extra: SettingsSectionViewModel =
        viewModel(factory = SettingsSectionViewModel.factory(LocalContext.current))
    val sectionVm: SectionPrefsViewModel =
        viewModel(factory = SectionPrefsViewModel.factory(LocalContext.current))
    val s by vm.settings.collectAsStateWithLifecycle()
    val categories by libraryVm.categories.collectAsStateWithLifecycle()
    val edited by sectionVm.editedSection.collectAsStateWithLifecycle()
    val sectionPrefs by sectionVm.settings.collectAsStateWithLifecycle()

    PrefSection("Categories") {
        Hint("Where newly added entries go. \"Always ask\" prompts on each add.")
        RadioRow("Always ask", s.defaultCategoryId == NO_DEFAULT_CATEGORY) {
            extra.setDefaultCategoryId(NO_DEFAULT_CATEGORY)
        }
        RadioRow("Default (uncategorised)", s.defaultCategoryId == 0L) {
            extra.setDefaultCategoryId(0L)
        }
        categories.forEach { category ->
            RadioRow(category.name, s.defaultCategoryId == category.id) {
                extra.setDefaultCategoryId(category.id)
            }
        }
        Hint(
            "Categories are created, renamed and reordered from the Library tab's " +
                "overflow menu — they live with the shelves they group.",
        )
    }

    HorizontalDivider()
    AppSectionSelector(
        edited = edited,
        onSelect = sectionVm::editSection,
        hint = "Layout and badges are kept per shelf — a grid that suits covers " +
            "rarely suits book spines. Pick which shelf the next two groups apply to.",
    )

    PrefSection("Display") {
        LibraryDisplayMode.entries.forEach { mode ->
            RadioRow(mode.label, sectionPrefs.libraryDisplayMode == mode) {
                sectionVm.setLibraryDisplayMode(mode)
            }
        }
    }

    PrefSection("Badges") {
        SwitchRow(
            title = "Show badges on covers",
            subtitle = "Master switch for every ${edited.label} cover badge",
            checked = sectionPrefs.showLibraryBadges,
            onCheckedChange = sectionVm::setShowLibraryBadges,
        )
        // Which badges are drawn is a global taste; whether any are drawn is
        // per shelf, so these follow the section's master switch.
        SwitchRow(
            title = "Unread count",
            checked = s.showUnreadBadge,
            enabled = sectionPrefs.showLibraryBadges,
            onCheckedChange = extra::setShowUnreadBadge,
        )
        SwitchRow(
            title = "Downloaded count",
            checked = s.showDownloadedBadge,
            enabled = sectionPrefs.showLibraryBadges,
            onCheckedChange = extra::setShowDownloadedBadge,
        )
        SwitchRow(
            title = "Source language",
            checked = s.showLanguageBadge,
            enabled = sectionPrefs.showLibraryBadges,
            onCheckedChange = extra::setShowLanguageBadge,
        )
    }
    HorizontalDivider()

    PrefSection("Global update") {
        Hint("How often Hibana checks your library's sources for new chapters.")
        UpdateSchedule.entries.forEach { option ->
            RadioRow(option.label, s.updateSchedule == option) { vm.setUpdateSchedule(option) }
        }
        if (s.updateSchedule != UpdateSchedule.MANUAL) {
            SwitchRow(
                title = "Only on Wi-Fi",
                subtitle = "Skip updates on metered connections",
                checked = s.updateOnWifiOnly,
                onCheckedChange = vm::setUpdateOnWifiOnly,
            )
        }
        if (s.updateSchedule == UpdateSchedule.WEEKLY || s.updateSchedule == UpdateSchedule.MONTHLY) {
            Text("Runs at %02d:%02d".format(s.updateHour, s.updateMinute), style = MaterialTheme.typography.bodyMedium)
            LabeledSlider("Hour", s.updateHour.toFloat(), 0f..23f) {
                vm.setUpdateTime(it.toInt(), s.updateMinute)
            }
            if (s.updateSchedule == UpdateSchedule.WEEKLY) {
                Text("Day of week", style = MaterialTheme.typography.bodyMedium)
                ChipRow(
                    options = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").withIndex().toList(),
                    label = { it.value },
                    isSelected = { s.updateDayOfWeek == it.index + 1 },
                    onSelect = { vm.setUpdateDayOfWeek(it.index + 1) },
                )
            } else {
                // Capped at 28 so the date exists in every month.
                LabeledSlider("Day of month", s.updateDayOfMonth.toFloat(), 1f..28f) {
                    vm.setUpdateDayOfMonth(it.toInt())
                }
            }
        }
    }
}

// --- reader -------------------------------------------------------------

@Composable
private fun ReaderSection(factory: ViewModelProvider.Factory) {
    val vm: SettingsViewModel = viewModel(factory = factory)
    val extra: SettingsSectionViewModel =
        viewModel(factory = SettingsSectionViewModel.factory(LocalContext.current))
    val sectionVm: SectionPrefsViewModel =
        viewModel(factory = SectionPrefsViewModel.factory(LocalContext.current))
    val s by vm.settings.collectAsStateWithLifecycle()
    val edited by sectionVm.editedSection.collectAsStateWithLifecycle()
    val sectionPrefs by sectionVm.settings.collectAsStateWithLifecycle()

    AppSectionSelector(
        edited = edited,
        onSelect = sectionVm::editSection,
        hint = "Comics and prose are rendered by two independent readers. " +
            "Pick which one these settings apply to.",
    )
    HorizontalDivider()

    // Only the controls the chosen reader can actually honour are rendered: a
    // page layout is meaningless for prose, a font size for a scan.
    when (edited) {
        AppSection.COMIC -> {
            PrefSection("Reading mode") {
                Hint("Paged right-to-left matches the Japanese manga convention.")
                ReadingMode.entries.forEach { mode ->
                    RadioRow(mode.label, sectionPrefs.readingMode == mode) {
                        sectionVm.setReadingMode(mode)
                    }
                }
            }

            PrefSection("Page layout") {
                val pagedMode = sectionPrefs.readingMode in
                    setOf(ReadingMode.PAGED_LTR, ReadingMode.PAGED_RTL, ReadingMode.PAGED_VERTICAL)
                Hint(
                    if (pagedMode) "Double page is best in landscape."
                    else "Only applies to paged reading modes.",
                )
                PageLayout.entries.forEach { layout ->
                    RadioRow(layout.label, s.comicPageLayout == layout, enabled = pagedMode) {
                        extra.setComicPageLayout(layout)
                    }
                }
            }

            PrefSection("Display") {
                SwitchRow(
                    title = "Fullscreen",
                    subtitle = "Hide the status and navigation bars while reading",
                    checked = s.comicFullscreen,
                    onCheckedChange = extra::setComicFullscreen,
                )
                KeepScreenOnRow(edited, sectionPrefs.keepScreenOn, sectionVm::setKeepScreenOn)
            }
        }

        AppSection.NOVEL -> {
            PrefSection("Typography") {
                LabeledSlider("Font size", sectionPrefs.fontScale, 0.8f..1.8f) {
                    sectionVm.setFontScale(it)
                }
                LabeledSlider("Line spacing", sectionPrefs.lineSpacing, 1.0f..2.2f) {
                    sectionVm.setLineSpacing(it)
                }
                ChipRow(
                    options = listOf("serif", "sans", "monospace"),
                    selected = sectionPrefs.fontFamily,
                    label = { it.replaceFirstChar { c -> c.uppercase() } },
                    onSelect = sectionVm::setFontFamily,
                )
            }

            PrefSection("Display") {
                KeepScreenOnRow(edited, sectionPrefs.keepScreenOn, sectionVm::setKeepScreenOn)
            }

            PrefSection("Theme") {
                Hint(
                    "The novel reader follows the app theme, which is shared with the rest " +
                        "of Hibana — change it under Appearance.",
                )
            }
        }
    }
}

@Composable
private fun KeepScreenOnRow(edited: AppSection, checked: Boolean, onChange: (Boolean) -> Unit) {
    SwitchRow(
        title = "Keep screen on",
        subtitle = "While reading ${edited.label}",
        checked = checked,
        onCheckedChange = onChange,
    )
}

// --- narration & translation -------------------------------------------

@Composable
private fun TtsTranslationSection(factory: ViewModelProvider.Factory) {
    val vm: SettingsViewModel = viewModel(factory = factory)
    val s by vm.settings.collectAsStateWithLifecycle()

    PrefSection("Text-to-speech") {
        LabeledSlider("Speed", s.ttsSpeed, 0.5f..2.0f) { vm.setTtsSpeed(it) }
        LabeledSlider("Pitch", s.ttsPitch, 0.5f..2.0f) { vm.setTtsPitch(it) }
        Text(
            "Voice: " + s.ttsVoice.ifBlank { "System default" },
            style = MaterialTheme.typography.bodyMedium,
        )
        Hint("Install extra voices from Android's own text-to-speech settings.")
        val context = LocalContext.current
        OutlinedButton(onClick = {
            // The engine owns its voice catalogue; sending users there beats
            // shipping a stale copy of the list.
            runCatching {
                context.startActivity(
                    Intent("com.android.settings.TTS_SETTINGS")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }) { Text("Open system voice settings") }
    }

    PrefSection("Narration language") {
        ChipRow(
            options = SpeechLanguage.entries,
            selected = s.ttsLanguage,
            label = { it.label },
            onSelect = vm::setTtsLanguage,
        )
    }

    PrefSection("Comic text recognition") {
        Hint("Script used to read text off comic pages for narration and translation.")
        ChipRow(
            options = OcrScriptSetting.entries,
            selected = s.ocrScript,
            label = { it.label },
            onSelect = vm::setOcrScript,
        )
    }

    PrefSection("Translation") {
        SwitchRow(
            title = "Translate chapters",
            checked = s.translateEnabled,
            onCheckedChange = vm::setTranslateEnabled,
        )
        Hint(
            "Translates Japanese, Korean, Chinese or English text into your chosen language. " +
                "Language packs download once over Wi-Fi (~30 MB each) and then work offline.",
        )
        ChipRow(
            options = TranslateLanguage.entries,
            selected = s.translateTarget,
            label = { it.label },
            onSelect = vm::setTranslateTarget,
        )
        val packStatus by vm.packStatus.collectAsStateWithLifecycle()
        OutlinedButton(onClick = vm::downloadTranslationPacks) {
            Text("Download all language packs now")
        }
        packStatus?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// --- downloads ----------------------------------------------------------

@Composable
private fun DownloadsSection(factory: ViewModelProvider.Factory) {
    val vm: SettingsViewModel = viewModel(factory = factory)
    val extra: SettingsSectionViewModel =
        viewModel(factory = SettingsSectionViewModel.factory(LocalContext.current))
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    PrefSection("Location") {
        Text(
            s.downloadLocation.ifBlank {
                context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        // TODO: allow picking a custom root via OpenDocumentTree once the
        // Downloader (owned elsewhere) can write through a SAF tree URI.
        Hint("Downloads are stored in app-private storage and removed when Hibana is uninstalled.")
    }

    PrefSection("Automatic downloads") {
        SwitchRow(
            title = "Download new chapters",
            subtitle = "Queue chapters as soon as a global update finds them",
            checked = s.downloadNewChapters,
            onCheckedChange = extra::setDownloadNewChapters,
        )
        SwitchRow(
            title = "Remove after reading",
            subtitle = "Delete a downloaded chapter once it is marked read",
            checked = s.removeAfterRead,
            onCheckedChange = extra::setRemoveAfterRead,
        )
    }

    PrefSection("Limits") {
        LabeledSlider(
            label = "Simultaneous downloads",
            value = s.concurrentDownloads.toFloat(),
            range = 1f..5f,
            steps = 3,
            format = { it.toInt().toString() },
        ) { extra.setConcurrentDownloads(it.toInt()) }
        Hint("Higher values finish sooner but make rate-limiting and bans more likely.")
    }
}

// --- browse -------------------------------------------------------------

@Composable
private fun BrowseSection(factory: ViewModelProvider.Factory, onOpenRepos: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = factory)
    val extra: SettingsSectionViewModel =
        viewModel(factory = SettingsSectionViewModel.factory(LocalContext.current))
    val s by vm.settings.collectAsStateWithLifecycle()

    PrefSection("Sources") {
        SwitchRow(
            title = "Include NSFW sources",
            subtitle = "Show sources flagged 18+ in Browse and global search",
            checked = s.includeNsfwSources,
            onCheckedChange = extra::setIncludeNsfwSources,
        )
        Hint("Hibana does not curate source content; this only hides sources that self-declare 18+.")
    }

    PrefSection("Extensions") {
        SwitchRow(
            title = "Auto-update extensions",
            subtitle = "Check enabled repositories for newer versions",
            checked = s.autoUpdateExtensions,
            onCheckedChange = extra::setAutoUpdateExtensions,
        )
        ActionRow(
            title = "Extension repositories",
            subtitle = "Add, enable and remove extension stores",
            onClick = onOpenRepos,
        )
    }
}

// --- data & storage -----------------------------------------------------

@Composable
private fun DataStorageSection(factory: ViewModelProvider.Factory) {
    val vm: SettingsViewModel = viewModel(factory = factory)
    val backupVm: BackupViewModel = viewModel(factory = factory)
    val extra: SettingsSectionViewModel =
        viewModel(factory = SettingsSectionViewModel.factory(LocalContext.current))
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val status by backupVm.status.collectAsStateWithLifecycle()
    val busy by backupVm.busy.collectAsStateWithLifecycle()
    val message by extra.message.collectAsStateWithLifecycle()

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip"),
    ) { uri: Uri? ->
        uri?.let { context.contentResolver.openOutputStream(it)?.let(backupVm::exportTo) }
    }
    val restoreBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let {
            val name = it.displayName(context)
            context.contentResolver.openInputStream(it)?.let { stream ->
                backupVm.importFrom(stream, isManatan = name.endsWith(".manatanbk", ignoreCase = true))
            }
        }
    }

    PrefSection("Backup & restore") {
        Hint("Hibana backups use the Mihon/Tachiyomi .tachibk format, so they restore into Mihon and vice-versa.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { createBackup.launch("hibana_backup_${System.currentTimeMillis()}.tachibk") },
                enabled = !busy,
            ) { Text("Create backup") }
            OutlinedButton(
                onClick = { restoreBackup.launch(arrayOf("*/*")) },
                enabled = !busy,
            ) { Text("Restore backup") }
        }
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text("Working…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }

    PrefSection("Automatic backups") {
        AutoBackupFrequency.entries.forEach { option ->
            RadioRow(option.label, s.autoBackupFrequency == option) { extra.setAutoBackupFrequency(option) }
        }
        // TODO: schedule the periodic worker once a backup destination tree URI
        // can be persisted; the preference is stored so scheduling can read it.
        Hint("Automatic backups need a folder you pick once; the schedule is stored and applied when that lands.")
    }

    PrefSection("Storage") {
        val maintenance: MaintenanceViewModel = viewModel(
            factory = remember(context) { MaintenanceViewModel.factory(context) },
        )
        val downloadedSize by maintenance.downloadedSize.collectAsStateWithLifecycle()
        val busy by maintenance.busy.collectAsStateWithLifecycle()
        var confirmClearDownloads by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) { maintenance.refreshCounts() }
        MaintenanceMessages(maintenance)

        ActionRow(
            title = "Clear downloaded chapters",
            subtitle = if (downloadedSize > 0) {
                "${formatBytes(downloadedSize)} stored offline"
            } else {
                "Nothing is downloaded"
            },
            enabled = !busy && downloadedSize > 0,
            onClick = { confirmClearDownloads = true },
        )

        if (confirmClearDownloads) {
            // Downloads are the only content the app holds that cannot be
            // re-fetched offline, so this confirms and states the cost.
            ConfirmDialog(
                title = "Clear downloaded chapters?",
                body = "This deletes every downloaded chapter file, freeing about " +
                    "${formatBytes(downloadedSize)}. Reading progress and bookmarks are kept, " +
                    "and chapters can be downloaded again.",
                confirmLabel = "Clear",
                onConfirm = { confirmClearDownloads = false; maintenance.clearDownloadCache() },
                onDismiss = { confirmClearDownloads = false },
            )
        }
        ActionRow(
            title = "Clear cookies",
            subtitle = "Sign out of every source and drop Cloudflare clearances",
            onClick = extra::clearCookies,
        )
        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// --- privacy ------------------------------------------------------------

@Composable
private fun PrivacySection(factory: ViewModelProvider.Factory) {
    val vm: SettingsViewModel = viewModel(factory = factory)
    val extra: SettingsSectionViewModel =
        viewModel(factory = SettingsSectionViewModel.factory(LocalContext.current))
    val s by vm.settings.collectAsStateWithLifecycle()

    PrefSection("Lock") {
        SwitchRow(
            title = "Require unlock",
            subtitle = "Ask for your device credential when Hibana opens",
            checked = s.appLockEnabled,
            onCheckedChange = extra::setAppLockEnabled,
        )
    }

    PrefSection("Screen") {
        SwitchRow(
            title = "Secure screen",
            subtitle = "Block screenshots and hide Hibana in the app switcher",
            checked = s.secureScreen,
            onCheckedChange = extra::setSecureScreen,
        )
    }

    PrefSection("Incognito") {
        SwitchRow(
            title = "Incognito mode",
            subtitle = "Stop recording history and reading progress",
            checked = s.incognitoMode,
            onCheckedChange = extra::setIncognitoMode,
        )
        Hint("Existing history is untouched; clear it from the History tab.")
    }
}

// --- advanced -----------------------------------------------------------

@Composable
private fun AdvancedSection(factory: ViewModelProvider.Factory) {
    val extra: SettingsSectionViewModel =
        viewModel(factory = SettingsSectionViewModel.factory(LocalContext.current))
    val updatesVm: UpdatesViewModel = viewModel(factory = factory)
    val context = LocalContext.current
    val message by extra.message.collectAsStateWithLifecycle()
    val refreshing by updatesVm.refreshing.collectAsStateWithLifecycle()
    val refreshProgress by updatesVm.progress.collectAsStateWithLifecycle()

    PrefSection("Maintenance") {
        ActionRow(
            title = "Refresh library metadata",
            subtitle = "Re-fetch details and chapter lists for every library entry",
            onClick = updatesVm::refresh,
            enabled = !refreshing,
        )
        refreshProgress?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        ActionRow(
            title = "Reset settings",
            subtitle = "Restore every preference to its default",
            onClick = extra::resetSettings,
        )
        val maintenance: MaintenanceViewModel = viewModel(
            factory = remember(context) { MaintenanceViewModel.factory(context) },
        )
        val cachedEntries by maintenance.cachedEntries.collectAsStateWithLifecycle()
        val maintenanceBusy by maintenance.busy.collectAsStateWithLifecycle()
        var confirmClearDatabase by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) { maintenance.refreshCounts() }
        MaintenanceMessages(maintenance)

        ActionRow(
            title = "Clear database",
            subtitle = if (cachedEntries > 0) {
                "$cachedEntries cached entr${if (cachedEntries == 1) "y" else "ies"} " +
                    "left over from browsing"
            } else {
                "Nothing cached outside your library"
            },
            enabled = !maintenanceBusy && cachedEntries > 0,
            onClick = { confirmClearDatabase = true },
        )

        if (confirmClearDatabase) {
            ConfirmDialog(
                title = "Clear database?",
                body = "This removes $cachedEntries entr" +
                    "${if (cachedEntries == 1) "y" else "ies"} that were cached while browsing " +
                    "but never added to your library. Library entries, their chapters, reading " +
                    "progress and history are all kept.",
                confirmLabel = "Clear",
                onConfirm = { confirmClearDatabase = false; maintenance.clearCachedEntries() },
                onDismiss = { confirmClearDatabase = false },
            )
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }

    PrefSection("Diagnostics") {
        ActionRow(
            title = "Dump crash logs",
            subtitle = "Share this process's recent logcat output",
            onClick = {
                // Read straight from logcat rather than keeping our own log file:
                // since Android 4.1 a process can only read its own entries, so
                // this is both sufficient and free of other apps' data.
                val log = runCatching {
                    Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "500"))
                        .inputStream.bufferedReader().use { it.readText() }
                }.getOrElse { "Could not read logcat: ${it.message}" }
                runCatching {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "Hibana logs")
                                .putExtra(Intent.EXTRA_TEXT, log.takeLast(MAX_SHARED_LOG_CHARS)),
                            "Share logs",
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }

    PrefSection("About") {
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        InfoRow("Version", versionName)
        InfoRow("Package", context.packageName)
        InfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        InfoRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
    }
}

/** Intent extras are bounded by the ~1 MB Binder buffer; stay well under it. */
private const val MAX_SHARED_LOG_CHARS = 200_000

// --- shared building blocks --------------------------------------------

/**
 * Picks which [AppSection]'s preferences the surrounding groups edit.
 *
 * Deliberately not tied to the section the user is browsing: settings is where
 * you go to fix the *other* half of the app.
 */
@Composable
private fun AppSectionSelector(
    edited: AppSection,
    onSelect: (AppSection) -> Unit,
    hint: String,
) {
    PrefSection("Applies to") {
        ChipRow(
            options = AppSection.entries,
            selected = edited,
            label = { it.label },
            onSelect = onSelect,
        )
        Hint(hint)
    }
}

@Composable
private fun PrefSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickableMinTouch { if (enabled) onSelect() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            label,
            Modifier.padding(start = 8.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f),
        )
    }
}

/** Surfaces a maintenance action's result once, then clears it. */
@Composable
private fun MaintenanceMessages(vm: MaintenanceViewModel) {
    val message by vm.message.collectAsStateWithLifecycle()
    message?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        LaunchedEffect(it) {
            kotlinx.coroutines.delay(6000)
            vm.consumeMessage()
        }
    }
}

/**
 * Confirmation for an irreversible action, stating exactly what goes.
 *
 * A destructive row that fires on a single tap gives no chance to notice a
 * mis-tap, and these cannot be undone.
 */
@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickableMinTouch { if (enabled) onClick() }
            .padding(vertical = 4.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        )
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.6f else 0.3f),
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    format: (Float) -> String = { "%.1f".format(it) },
    onChange: (Float) -> Unit,
) {
    Column {
        Text("$label: ${format(value)}", style = MaterialTheme.typography.bodyMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

/**
 * Chip picker. [isSelected] exists for option types whose identity isn't the
 * chip's value (e.g. an index/label pair), where `==` on the option is wrong.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    selected: T? = null,
    isSelected: (T) -> Boolean = { it == selected },
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = isSelected(option),
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

private fun Uri.displayName(context: android.content.Context): String {
    context.contentResolver.query(this, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx) ?: ""
    }
    return lastPathSegment ?: ""
}
