package com.opennovel.reader.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opennovel.reader.data.OcrScriptSetting
import com.opennovel.reader.data.ReadingMode
import com.opennovel.reader.data.SpeechLanguage
import com.opennovel.reader.data.ThemeMode
import com.opennovel.reader.data.TranslateLanguage
import com.opennovel.reader.data.UpdateSchedule
import com.opennovel.reader.ui.BackupViewModel
import com.opennovel.reader.ui.SettingsViewModel

@Composable
fun SettingsScreen(factory: ViewModelProvider.Factory) {
    val vm: SettingsViewModel = viewModel(factory = factory)
    val s by vm.settings.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Section("Theme") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = s.themeMode == mode,
                        onClick = { vm.setTheme(mode) },
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
        }

        Section("Reader") {
            LabeledSlider("Font size", s.fontScale, 0.8f..1.8f) { vm.setFontScale(it) }
            LabeledSlider("Line spacing", s.lineSpacing, 1.0f..2.2f) { vm.setLineSpacing(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("serif", "sans", "monospace").forEach { f ->
                    FilterChip(
                        selected = s.fontFamily == f,
                        onClick = { vm.setFontFamily(f) },
                        label = { Text(f.replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Keep screen on", Modifier.weight(1f))
                Switch(checked = s.keepScreenOn, onCheckedChange = { vm.setKeepScreenOn(it) })
            }
        }

        Section("Reading mode (manga)") {
            Text(
                "How comic pages are laid out. Paged right-to-left matches Japanese manga.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            ReadingMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth().clickableMinTouch { vm.setReadingMode(mode) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.readingMode == mode, onClick = null)
                    Text(mode.label, Modifier.padding(start = 8.dp))
                }
            }
        }

        Section("Text-to-speech") {
            LabeledSlider("Speed", s.ttsSpeed, 0.5f..2.0f) { vm.setTtsSpeed(it) }
            LabeledSlider("Pitch", s.ttsPitch, 0.5f..2.0f) { vm.setTtsPitch(it) }
            Text("Narration language", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpeechLanguage.entries.forEach { lang ->
                    FilterChip(
                        selected = s.ttsLanguage == lang,
                        onClick = { vm.setTtsLanguage(lang) },
                        label = { Text(lang.label) },
                    )
                }
            }
        }

        Section("Manga text recognition") {
            Text(
                "Script used to read text off manga pages for narration and translation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OcrScriptSetting.entries.forEach { script ->
                    FilterChip(
                        selected = s.ocrScript == script,
                        onClick = { vm.setOcrScript(script) },
                        label = { Text(script.label) },
                    )
                }
            }
        }

        Section("Library updates") {
            Text(
                "How often Hibana checks your library's sources for new chapters.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            UpdateSchedule.entries.forEach { option ->
                Row(
                    Modifier.fillMaxWidth().clickableMinTouch { vm.setUpdateSchedule(option) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.updateSchedule == option, onClick = null)
                    Text(option.label, Modifier.padding(start = 8.dp))
                }
            }
            if (s.updateSchedule != UpdateSchedule.MANUAL) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Only on Wi-Fi", Modifier.weight(1f))
                    Switch(checked = s.updateOnWifiOnly, onCheckedChange = { vm.setUpdateOnWifiOnly(it) })
                }
            }
            if (s.updateSchedule == UpdateSchedule.WEEKLY || s.updateSchedule == UpdateSchedule.MONTHLY) {
                Text(
                    "Runs at %02d:%02d".format(s.updateHour, s.updateMinute),
                    style = MaterialTheme.typography.bodyMedium,
                )
                LabeledSlider("Hour", s.updateHour.toFloat(), 0f..23f) {
                    vm.setUpdateTime(it.toInt(), s.updateMinute)
                }
                if (s.updateSchedule == UpdateSchedule.WEEKLY) {
                    Text("Day of week", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                            .forEachIndexed { index, day ->
                                FilterChip(
                                    selected = s.updateDayOfWeek == index + 1,
                                    onClick = { vm.setUpdateDayOfWeek(index + 1) },
                                    label = { Text(day) },
                                )
                            }
                    }
                } else {
                    // Capped at 28 so the date exists in every month.
                    LabeledSlider("Day of month", s.updateDayOfMonth.toFloat(), 1f..28f) {
                        vm.setUpdateDayOfMonth(it.toInt())
                    }
                }
            }
        }

        Section("Translation") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Translate chapters", Modifier.weight(1f))
                Switch(checked = s.translateEnabled, onCheckedChange = { vm.setTranslateEnabled(it) })
            }
            Text(
                "Translates Japanese, Korean, Chinese or English text into your chosen language. " +
                    "Language packs download once over Wi-Fi (~30 MB each) and then work offline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TranslateLanguage.entries.forEach { lang ->
                    FilterChip(
                        selected = s.translateTarget == lang,
                        onClick = { vm.setTranslateTarget(lang) },
                        label = { Text(lang.label) },
                    )
                }
            }
            // ML Kit ships no bundled translation models, so the nearest thing to
            // shipping them is fetching everything up front.
            val packStatus by vm.packStatus.collectAsStateWithLifecycle()
            OutlinedButton(onClick = vm::downloadTranslationPacks) {
                Text("Download all language packs now")
            }
            packStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }

        BackupSection(factory)
    }
}

@Composable
private fun BackupSection(factory: ViewModelProvider.Factory) {
    val vm: BackupViewModel = viewModel(factory = factory)
    val context = LocalContext.current
    val status by vm.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip"),
    ) { uri: Uri? ->
        uri?.let { context.contentResolver.openOutputStream(it)?.let(vm::exportTo) }
    }
    val restoreBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let {
            val name = it.displayName(context)
            context.contentResolver.openInputStream(it)?.let { stream ->
                vm.importFrom(stream, isManatan = name.endsWith(".manatanbk", ignoreCase = true))
            }
        }
    }

    Section("Backup & restore") {
        Text(
            "Hibana backups use the Mihon/Tachiyomi .tachibk format, so they restore into Mihon and vice-versa.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
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
}

private fun Uri.displayName(context: android.content.Context): String {
    context.contentResolver.query(this, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx) ?: ""
    }
    return lastPathSegment ?: ""
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Text("$label: ${"%.1f".format(value)}", style = MaterialTheme.typography.bodyMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}


