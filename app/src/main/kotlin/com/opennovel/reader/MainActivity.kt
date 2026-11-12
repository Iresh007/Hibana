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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opennovel.reader.ui.RootNav
import com.opennovel.reader.ui.SettingsViewModel
import com.opennovel.reader.ui.AppLock
import com.opennovel.reader.ui.VmFactory
import androidx.compose.foundation.layout.fillMaxSize
import kotlinx.coroutines.flow.first
import com.opennovel.reader.ui.theme.OpenNovelTheme

class MainActivity : androidx.fragment.app.FragmentActivity() {
    private var backgroundedAt = 0L
    private val locked = androidx.compose.runtime.mutableStateOf(true)
    private var lockPrompting = false

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
                val needsUnlock = settings.appLockEnabled && locked.value &&
                    AppLock.canAuthenticate(this@MainActivity, settings.lockWithBiometrics)
                if (needsUnlock) {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.fillMaxSize(),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        androidx.compose.material3.Button(onClick = { requestUnlock(settings.lockWithBiometrics) }) {
                            androidx.compose.material3.Text("Unlock Hibana")
                        }
                    }
                    androidx.compose.runtime.LaunchedEffect(Unit) { requestUnlock(settings.lockWithBiometrics) }
                } else {
                    RootNav(factory = factory)
                }
            }
        }
    }

    private fun requestUnlock(allowBiometrics: Boolean) {
        if (lockPrompting) return
        lockPrompting = true
        AppLock.prompt(this, allowBiometrics) { ok ->
            lockPrompting = false
            if (ok) locked.value = false
        }
    }

    override fun onStop() {
        super.onStop()
        backgroundedAt = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        if (backgroundedAt == 0L) return
        val container = (application as NovelReaderApp).container
        lifecycleScope.launch {
            val prefs = container.settingsRepository.settings.first()
            val away = System.currentTimeMillis() - backgroundedAt
            val relock = when (prefs.lockTimeout) {
                com.opennovel.reader.data.LockTimeout.NEVER -> false
                com.opennovel.reader.data.LockTimeout.ALWAYS -> true
                else -> away >= prefs.lockTimeout.minutes * 60_000L
            }
            if (prefs.appLockEnabled && relock) locked.value = true
        }
    }
}
