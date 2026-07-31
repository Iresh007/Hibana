package com.opennovel.reader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opennovel.reader.data.ThemeMode
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

        Section("Text-to-speech") {
            LabeledSlider("Speed", s.ttsSpeed, 0.5f..2.0f) { vm.setTtsSpeed(it) }
            LabeledSlider("Pitch", s.ttsPitch, 0.5f..2.0f) { vm.setTtsPitch(it) }
        }
    }
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
