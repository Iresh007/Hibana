package com.opennovel.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opennovel.reader.ui.RootNav
import com.opennovel.reader.ui.SettingsViewModel
import com.opennovel.reader.ui.VmFactory
import com.opennovel.reader.ui.theme.OpenNovelTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NovelReaderApp).container
        val factory = VmFactory(container)
        setContent {
            val settingsVm: SettingsViewModel = viewModel(factory = factory)
            val settings by settingsVm.settings.collectAsStateWithLifecycle()
            OpenNovelTheme(themeMode = settings.themeMode) {
                RootNav(factory = factory)
            }
        }
    }
}
