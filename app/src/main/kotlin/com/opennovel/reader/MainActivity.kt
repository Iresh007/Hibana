package com.opennovel.reader

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
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

        // Library-update and download notifications are silently dropped on
        // Android 13+ unless the user has granted this at runtime.
        if (Build.VERSION.SDK_INT >= 33) {
            val askNotifications =
                registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // "Secure screen" hides the app from screenshots and the recents preview.
        lifecycleScope.launch {
            container.settingsRepository.settings
                .map { it.secureScreen }
                .distinctUntilChanged()
                .collect { secure ->
                    if (secure) window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE,
                    ) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
        }
        val factory = VmFactory(container)
        setContent {
            val settingsVm: SettingsViewModel = viewModel(factory = factory)
            val settings by settingsVm.settings.collectAsStateWithLifecycle()
            OpenNovelTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, appTheme = settings.appTheme) {
                RootNav(factory = factory)
            }
        }
    }
}
