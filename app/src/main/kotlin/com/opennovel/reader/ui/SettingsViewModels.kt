package com.opennovel.reader.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.opennovel.reader.data.AppSection
import com.opennovel.reader.data.AutoBackupFrequency
import com.opennovel.reader.data.LibraryDisplayMode
import com.opennovel.reader.data.PageLayout
import com.opennovel.reader.data.ReaderSettings
import com.opennovel.reader.data.ReadingMode
import com.opennovel.reader.data.SectionRepository
import com.opennovel.reader.data.SectionSettings
import com.opennovel.reader.data.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Setters for the preferences introduced by the split settings tree.
 *
 * Kept apart from [SettingsViewModel] (and from [VmFactory]) so the settings
 * tree can grow without editing the shared factory. It builds its own
 * [SettingsRepository]: DataStore's `preferencesDataStore` delegate returns one
 * process-wide instance per file name, so a second repository object reads and
 * writes exactly the same store as the container's.
 */
class SettingsSectionViewModel(private val repo: SettingsRepository) : ViewModel() {

    val settings: StateFlow<ReaderSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())

    /** One-shot feedback line for destructive/maintenance actions. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() { _message.value = null }

    fun setDynamicColor(v: Boolean) = viewModelScope.launch { repo.setDynamicColor(v) }
    fun setAppLanguage(v: String) = viewModelScope.launch { repo.setAppLanguage(v) }
    fun setDefaultCategoryId(v: Long) = viewModelScope.launch { repo.setDefaultCategoryId(v) }
    fun setShowUnreadBadge(v: Boolean) = viewModelScope.launch { repo.setShowUnreadBadge(v) }
    fun setShowDownloadedBadge(v: Boolean) = viewModelScope.launch { repo.setShowDownloadedBadge(v) }
    fun setShowLanguageBadge(v: Boolean) = viewModelScope.launch { repo.setShowLanguageBadge(v) }
    fun setComicPageLayout(v: PageLayout) = viewModelScope.launch { repo.setComicPageLayout(v) }
    fun setComicFullscreen(v: Boolean) = viewModelScope.launch { repo.setComicFullscreen(v) }
    fun setDownloadNewChapters(v: Boolean) = viewModelScope.launch { repo.setDownloadNewChapters(v) }
    fun setRemoveAfterRead(v: Boolean) = viewModelScope.launch { repo.setRemoveAfterRead(v) }
    fun setConcurrentDownloads(v: Int) = viewModelScope.launch { repo.setConcurrentDownloads(v) }
    fun setIncludeNsfwSources(v: Boolean) = viewModelScope.launch { repo.setIncludeNsfwSources(v) }
    fun setAutoUpdateExtensions(v: Boolean) = viewModelScope.launch { repo.setAutoUpdateExtensions(v) }
    fun setAutoBackupFrequency(v: AutoBackupFrequency) =
        viewModelScope.launch { repo.setAutoBackupFrequency(v) }
    fun setAppLockEnabled(v: Boolean) = viewModelScope.launch { repo.setAppLockEnabled(v) }
    fun setSecureScreen(v: Boolean) = viewModelScope.launch { repo.setSecureScreen(v) }
    fun setIncognitoMode(v: Boolean) = viewModelScope.launch { repo.setIncognitoMode(v) }

    fun resetSettings() = viewModelScope.launch {
        repo.resetAll()
        _message.value = "Settings reset to defaults"
    }

    /** Drops WebView cookies, which is what source logins and Cloudflare use. */
    fun clearCookies() {
        runCatching {
            val cookies = android.webkit.CookieManager.getInstance()
            cookies.removeAllCookies(null)
            cookies.flush()
        }
        _message.value = "Cookies cleared"
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsSectionViewModel(SettingsRepository(appContext)) as T
            }
        }
    }
}

/**
 * Edits [SectionSettings] for one [AppSection] at a time.
 *
 * The edited section is deliberately independent of the section the user is
 * currently browsing: someone reading manga must still be able to fix their
 * novel font size without leaving settings. It only *starts* on the active
 * section because that is the likelier target.
 *
 * Builds its own [SectionRepository] for the same reason
 * [SettingsSectionViewModel] does — the shared factory is off-limits here, and
 * DataStore hands back one process-wide instance per file name, so this reads
 * and writes the container's store.
 */
class SectionPrefsViewModel(private val repo: SectionRepository) : ViewModel() {

    private val _editedSection = MutableStateFlow(AppSection.COMIC)
    val editedSection: StateFlow<AppSection> = _editedSection.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val settings: StateFlow<SectionSettings> = _editedSection
        .flatMapLatest { repo.settings(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SectionSettings())

    init {
        viewModelScope.launch { _editedSection.value = repo.activeSection.first() }
    }

    fun editSection(section: AppSection) { _editedSection.value = section }

    private fun edit(block: suspend (AppSection) -> Unit) {
        val target = _editedSection.value
        viewModelScope.launch { block(target) }
    }

    fun setFontScale(v: Float) = edit { repo.setFontScale(it, v) }
    fun setLineSpacing(v: Float) = edit { repo.setLineSpacing(it, v) }
    fun setFontFamily(v: String) = edit { repo.setFontFamily(it, v) }
    fun setReadingMode(v: ReadingMode) = edit { repo.setReadingMode(it, v) }
    fun setKeepScreenOn(v: Boolean) = edit { repo.setKeepScreenOn(it, v) }
    fun setLibraryDisplayMode(v: LibraryDisplayMode) = edit { repo.setLibraryDisplayMode(it, v) }
    fun setShowLibraryBadges(v: Boolean) = edit { repo.setShowLibraryBadges(it, v) }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SectionPrefsViewModel(SectionRepository(appContext)) as T
            }
        }
    }
}
