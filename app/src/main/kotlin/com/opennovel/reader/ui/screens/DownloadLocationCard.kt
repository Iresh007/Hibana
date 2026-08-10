package com.opennovel.reader.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opennovel.reader.download.DownloadStorage

/**
 * Lets the user move downloads out of app-private storage.
 *
 * App-private files are invisible to file managers and are deleted with the app,
 * so a large offline library silently disappears on uninstall. Picking a folder
 * through the Storage Access Framework needs no runtime permission — the grant
 * is the folder itself — which is why this is a picker rather than a path field.
 */
@Composable
fun DownloadLocationCard() {
    val context = LocalContext.current
    val storage = remember { DownloadStorage(context) }
    var label by remember { mutableStateOf(storage.label()) }
    var custom by remember { mutableStateOf(storage.isCustom) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && storage.set(uri)) {
            label = storage.label()
            custom = true
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Download location", style = MaterialTheme.typography.titleSmall)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (custom) {
                "New downloads are saved here and survive uninstalling Hibana."
            } else {
                "Downloads are in app-private storage and are removed when Hibana is uninstalled."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Row {
            OutlinedButton(onClick = { picker.launch(null) }) {
                Text(if (custom) "Change folder" else "Choose folder")
            }
            if (custom) {
                Spacer(Modifier.width(8.dp))
                // Only new downloads move; anything already written keeps its
                // recorded location and stays readable.
                TextButton(onClick = {
                    storage.clear()
                    label = storage.label()
                    custom = false
                }) { Text("Use app storage") }
            }
        }
    }
    HorizontalDivider()
}
