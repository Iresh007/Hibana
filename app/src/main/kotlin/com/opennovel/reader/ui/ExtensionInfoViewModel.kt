package com.opennovel.reader.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.opennovel.reader.NovelReaderApp
import com.opennovel.reader.extension.Ecosystem
import com.opennovel.reader.extension.ExtensionInfo
import com.opennovel.reader.extension.SourcePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Per-extension preferences that have no home in the extension layer itself:
 * they describe how *this app* treats an extension, not the extension package.
 */
private val Context.extensionPrefs by preferencesDataStore(name = "extension_prefs")

/** A source contributed by an extension, flattened for display. */
data class ExtensionSourceInfo(
    val id: Long,
    val name: String,
    val lang: String,
    val baseUrl: String,
    /** Whether the extension declares a settings screen for this source. */
    val hasPreferences: Boolean = false,
)

data class ExtensionInfoUiState(
    val info: ExtensionInfo? = null,
    val sources: List<ExtensionSourceInfo> = emptyList(),
    val enabled: Boolean = true,
    val incognito: Boolean = false,
)

/**
 * Backs the single-extension detail screen.
 *
 * Reads straight from the app container rather than taking constructor
 * dependencies, because [VmFactory] is shared and this ViewModel needs a
 * per-screen argument (the package id) that the factory can't supply.
 */
class ExtensionInfoViewModel(
    private val appContext: Context,
    private val packageName: String,
) : ViewModel() {

    private val container = (appContext as NovelReaderApp).container

    private val disabledPkgs: Flow<Set<String>> =
        appContext.extensionPrefs.data.map { it[DISABLED] ?: emptySet() }

    private val incognitoPkgs: Flow<Set<String>> =
        appContext.extensionPrefs.data.map { it[INCOGNITO] ?: emptySet() }

    val state: StateFlow<ExtensionInfoUiState> = combine(
        container.extensionManager.installed,
        container.sourceManager.sources,
        disabledPkgs,
        incognitoPkgs,
    ) { installed, sources, disabled, incognito ->
        val info = installed.firstOrNull { it.pkgId == packageName }
        ExtensionInfoUiState(
            info = info,
            // A loaded source doesn't record which package produced it, so this
            // mirrors ExtensionsViewModel.sourceIdsFor and matches by name.
            sources = if (info == null) {
                emptyList()
            } else {
                sources
                    .filter { it.name.equals(info.name, true) || info.name.contains(it.name, true) }
                    .map {
                        ExtensionSourceInfo(
                            id = it.id,
                            name = it.name,
                            lang = it.lang,
                            baseUrl = it.baseUrl,
                            hasPreferences = SourcePreferences.isConfigurable(it),
                        )
                    }
            },
            enabled = packageName !in disabled,
            incognito = packageName in incognito,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ExtensionInfoUiState())

    /** Refreshes the shared installed list so the screen has data on a cold open. */
    fun refresh() {
        viewModelScope.launch { runCatching { container.extensionManager.loadInstalled() } }
    }

    /**
     * Disabling takes effect immediately by unregistering the extension's
     * sources; re-enabling reloads them, so neither needs an app restart.
     */
    fun setEnabled(on: Boolean) {
        viewModelScope.launch {
            appContext.extensionPrefs.edit { prefs ->
                val current = prefs[DISABLED] ?: emptySet()
                prefs[DISABLED] = if (on) current - packageName else current + packageName
            }
            val info = state.value.info ?: return@launch
            if (on) {
                container.extensionLoaders.firstOrNull { it.ecosystem == info.ecosystem }?.let { loader ->
                    runCatching { loader.load(info) }.getOrDefault(emptyList())
                        .forEach(container.sourceManager::register)
                }
            } else {
                state.value.sources.forEach { container.sourceManager.unregister(it.id) }
            }
        }
    }

    fun setIncognito(on: Boolean) {
        viewModelScope.launch {
            appContext.extensionPrefs.edit { prefs ->
                val current = prefs[INCOGNITO] ?: emptySet()
                prefs[INCOGNITO] = if (on) current + packageName else current - packageName
            }
        }
    }

    /** Only APK ecosystems are real packages; JS plugins have nothing to hand the installer. */
    val isPackage: Boolean get() = state.value.info?.ecosystem != Ecosystem.LNREADER

    fun uninstall() {
        runCatching {
            appContext.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun openInBrowser(url: String) {
        if (url.isBlank()) return
        runCatching {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private companion object {
        val DISABLED = stringSetPreferencesKey("disabled_extensions")
        val INCOGNITO = stringSetPreferencesKey("incognito_extensions")
    }
}

/** Supplies the package id that the shared [VmFactory] can't carry. */
fun extensionInfoViewModelFactory(context: Context, packageName: String): ViewModelProvider.Factory {
    val app = context.applicationContext
    return object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ExtensionInfoViewModel(app, packageName) as T
    }
}
