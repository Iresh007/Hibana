package com.opennovel.reader.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.opennovel.reader.NovelReaderApp
import com.opennovel.reader.extension.SourcePreferences
import com.opennovel.reader.extension.SourcePreferenceItem

/**
 * The settings an extension declares for one of its sources — mirror domain,
 * login, preferred quality, chapter language and so on.
 *
 * Many Keiyoushi sources return nothing at all until a mirror is chosen, so
 * without this screen an unconfigured source is indistinguishable from a broken
 * one. Values are read from and written back to the extension's own
 * SharedPreferences file, so a change takes effect on the next request without
 * reloading the extension.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcePreferencesScreen(
    sourceId: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    // Built once per source: setupPreferenceScreen runs extension code, and it
    // is not required to be cheap or idempotent.
    val screen = remember(sourceId) {
        val container = (context.applicationContext as NovelReaderApp).container
        val source = container.sourceManager.get(sourceId)
        val configurable = SourcePreferences.configurableOf(source)
        val items = configurable?.let { SourcePreferences.read(context, it) }.orEmpty()
        (source?.name ?: "Source") to items
    }
    val (sourceName, items) = screen

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sourceName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text(
                    "This source has no settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(32.dp),
                )
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(items) { item ->
                when (item) {
                    is SourcePreferenceItem.Group -> GroupHeader(item.title)
                    is SourcePreferenceItem.Info -> PreferenceRow(item.title, item.summary, true) {}
                    is SourcePreferenceItem.Switch -> SwitchPreferenceRow(item)
                    is SourcePreferenceItem.Text -> TextPreferenceRow(item)
                    is SourcePreferenceItem.Select -> SelectPreferenceRow(item)
                    is SourcePreferenceItem.MultiSelect -> MultiSelectPreferenceRow(item)
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String) {
    if (title.isBlank()) return
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun PreferenceRow(
    title: String,
    summary: String?,
    enabled: Boolean,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickableMinTouch(onClick) else Modifier.heightIn(min = 48.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                },
            )
            if (!summary.isNullOrBlank()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
private fun SwitchPreferenceRow(item: SourcePreferenceItem.Switch) {
    var checked by remember(item.native) { mutableStateOf(item.value) }
    val apply = { next: Boolean ->
        SourcePreferences.write(item.native, next)?.let { checked = it }
    }
    PreferenceRow(
        title = item.title,
        summary = item.summary,
        enabled = item.enabled,
        trailing = {
            Switch(checked = checked, onCheckedChange = { apply(it) }, enabled = item.enabled)
        },
        onClick = { apply(!checked) },
    )
}

@Composable
private fun TextPreferenceRow(item: SourcePreferenceItem.Text) {
    var value by remember(item.native) { mutableStateOf(item.value) }
    var editing by remember(item.native) { mutableStateOf(false) }
    var draft by remember(item.native) { mutableStateOf(item.value) }

    PreferenceRow(
        title = item.title,
        // The stored value is the useful summary when the extension didn't
        // supply one — an empty mirror field is the whole problem being solved.
        summary = item.summary ?: value.ifBlank { "Not set" },
        enabled = item.enabled,
        onClick = { draft = value; editing = true },
    )

    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(item.title) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    SourcePreferences.write(item.native, draft)?.let { value = it }
                    editing = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SelectPreferenceRow(item: SourcePreferenceItem.Select) {
    var value by remember(item.native) { mutableStateOf(item.value) }
    var open by remember(item.native) { mutableStateOf(false) }

    PreferenceRow(
        title = item.title,
        summary = item.summary ?: labelFor(item.entries, item.entryValues, value),
        enabled = item.enabled,
        onClick = { open = true },
    )

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(item.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    item.entryValues.forEachIndexed { index, entryValue ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = entryValue == value,
                                    onClick = {
                                        SourcePreferences.write(item.native, entryValue)?.let { value = it }
                                        open = false
                                    },
                                )
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = entryValue == value, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(item.entries.getOrElse(index) { entryValue })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MultiSelectPreferenceRow(item: SourcePreferenceItem.MultiSelect) {
    var value by remember(item.native) { mutableStateOf(item.value) }
    var draft by remember(item.native) { mutableStateOf(item.value) }
    var open by remember(item.native) { mutableStateOf(false) }

    PreferenceRow(
        title = item.title,
        summary = item.summary ?: value
            .map { labelFor(item.entries, item.entryValues, it) }
            .sorted()
            .joinToString()
            .ifBlank { "None selected" },
        enabled = item.enabled,
        onClick = { draft = value; open = true },
    )

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(item.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    item.entryValues.forEachIndexed { index, entryValue ->
                        val checked = entryValue in draft
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = checked,
                                    onValueChange = { on ->
                                        draft = if (on) draft + entryValue else draft - entryValue
                                    },
                                )
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.width(12.dp))
                            Text(item.entries.getOrElse(index) { entryValue })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    SourcePreferences.write(item.native, draft)?.let { value = it }
                    open = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

/** Entry values are opaque ids; show the human label the extension paired with it. */
private fun labelFor(entries: List<String>, entryValues: List<String>, value: String): String {
    val index = entryValues.indexOf(value)
    return entries.getOrNull(index) ?: value
}
