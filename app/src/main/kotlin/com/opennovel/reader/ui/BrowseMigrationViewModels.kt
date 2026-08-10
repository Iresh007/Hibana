package com.opennovel.reader.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.opennovel.reader.NovelReaderApp
import com.opennovel.reader.data.AppSection
import com.opennovel.reader.extension.SourcePreferences
import com.opennovel.reader.migration.MigrationCandidate
import com.opennovel.reader.migration.MigrationOptions
import com.opennovel.reader.migration.MigrationSearch
import com.opennovel.reader.source.ImageChapterSource
import com.opennovel.reader.source.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Browse-tab preferences. Kept out of the shared settings store because pinning
 * is a per-source scratch preference, not part of the user's backed-up config.
 */
private val Context.browsePrefs by preferencesDataStore(name = "browse_prefs")

private val PINNED_SOURCES = stringSetPreferencesKey("pinned_sources")

/** One row of the Sources list. */
data class BrowseSource(
    val id: Long,
    val name: String,
    val lang: String,
    val baseUrl: String,
    /** Owning extension package, or empty when the source has no package (JS plugins). */
    val pkgId: String,
    val supportsLatest: Boolean,
    val pinned: Boolean,
    /** Whether the extension declares a settings screen for this source. */
    val hasPreferences: Boolean = false,
)

/**
 * Which section a source belongs to, decided by what it can actually serve
 * rather than by the ecosystem it shipped in.
 *
 * A [Source] carries no ecosystem, and the ecosystems are not clean proxies
 * anyway. Only comic sources can produce page images, so the same capability
 * test [com.opennovel.reader.data.LibraryRepository] uses to classify entries
 * also decides which catalogue a source appears in — a source and the entries
 * taken from it can then never land in different sections.
 */
internal fun sectionOf(source: Source): AppSection =
    if (source is ImageChapterSource) AppSection.COMIC else AppSection.NOVEL

/**
 * The active section and the sources that belong to it.
 *
 * Separate from the screens' own ViewModels because Browse, Extensions and
 * Migrate each need the same scope and none of them owns it.
 */
class SectionScopeViewModel(appContext: Context) : ViewModel() {

    private val container = (appContext as NovelReaderApp).container

    val section: StateFlow<AppSection> = container.sectionRepository.activeSection
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSection.COMIC)

    val sourceIds: StateFlow<Set<Long>> = combine(
        container.sourceManager.sources,
        container.sectionRepository.activeSection,
    ) { sources, active ->
        sources.filter { sectionOf(it) == active }.map { it.id }.toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val app = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SectionScopeViewModel(app) as T
            }
        }
    }
}

/**
 * Backs the Mihon-style source list: what's installed, which are pinned, and the
 * per-source actions that need an Android context (browser, share, cookies).
 *
 * Reaches the graph through the application rather than the shared [VmFactory]
 * because it needs a [Context] the factory doesn't supply.
 */
class SourceListViewModel(private val appContext: Context) : ViewModel() {

    private val container = (appContext as NovelReaderApp).container

    private val pinnedIds: Flow<Set<String>> =
        appContext.browsePrefs.data.map { it[PINNED_SOURCES] ?: emptySet() }

    val section: StateFlow<AppSection> = container.sectionRepository.activeSection
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSection.COMIC)

    val sources: StateFlow<List<BrowseSource>> = combine(
        container.sourceManager.sources,
        container.extensionManager.installed,
        pinnedIds,
        container.sectionRepository.activeSection,
    ) { sources, installed, pins, active ->
        sources.filter { sectionOf(it) == active }.map { source ->
            BrowseSource(
                id = source.id,
                name = source.name,
                lang = source.lang,
                baseUrl = source.baseUrl,
                // A loaded source doesn't record which package produced it, so
                // this mirrors ExtensionsViewModel.sourceIdsFor and matches by name.
                pkgId = installed.firstOrNull {
                    it.name.equals(source.name, true) || it.name.contains(source.name, true)
                }?.pkgId.orEmpty(),
                supportsLatest = source.supportsLatest,
                pinned = source.id.toString() in pins,
                hasPreferences = SourcePreferences.isConfigurable(source),
            )
        }.sortedBy { it.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Package id per source id, so the Migrate tab can show the same icon. */
    val packageIds: StateFlow<Map<Long, String>> = sources
        .map { list -> list.filter { it.pkgId.isNotEmpty() }.associate { it.id to it.pkgId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun togglePin(sourceId: Long) {
        viewModelScope.launch {
            appContext.browsePrefs.edit { prefs ->
                val key = sourceId.toString()
                val current = prefs[PINNED_SOURCES] ?: emptySet()
                prefs[PINNED_SOURCES] = if (key in current) current - key else current + key
            }
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

    fun share(url: String, title: String) {
        if (url.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, url)
        }
        runCatching {
            appContext.startActivity(
                Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Expires this site's cookies only. A global wipe would sign the user out of
     * every other source too, which is never what "clear cookies" on one row means.
     */
    fun clearCookies(url: String) {
        if (url.isBlank()) return
        runCatching {
            val manager = CookieManager.getInstance()
            val host = Uri.parse(url).host ?: return@runCatching
            val cookies = manager.getCookie(url) ?: return@runCatching
            cookies.split(';').forEach { pair ->
                val name = pair.substringBefore('=').trim()
                if (name.isNotEmpty()) {
                    manager.setCookie(url, "$name=; Max-Age=0; path=/; domain=$host")
                }
            }
            manager.flush()
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val app = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SourceListViewModel(app) as T
            }
        }
    }
}

/** A library entry offered up for migration. */
data class MigrateEntry(
    val id: Long,
    val title: String,
    val coverUrl: String?,
)

/**
 * Drives the whole migration wizard: pick entries, pick what carries over, pick
 * target sources, then review candidates.
 *
 * Nothing is written until the final confirmation — a bad title match would
 * otherwise destroy reading progress silently.
 */
class MigrationFlowViewModel(appContext: Context) : ViewModel() {

    private val container = (appContext as NovelReaderApp).container
    private val repo = container.libraryRepository
    private val migrations = container.migrationManager
    private val sourceManager = container.sourceManager

    val section: StateFlow<AppSection> = container.sectionRepository.activeSection
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSection.COMIC)

    // --- step 1: which entries ---

    private val _entries = MutableStateFlow<List<MigrateEntry>?>(null)
    val entries: StateFlow<List<MigrateEntry>?> = _entries.asStateFlow()

    private val _checked = MutableStateFlow<Set<Long>>(emptySet())
    val checked: StateFlow<Set<Long>> = _checked.asStateFlow()

    /** Everything from one source starts checked: migrating the lot is the usual intent. */
    fun loadEntries(sourceId: Long) {
        viewModelScope.launch {
            val novels = repo.observeLibrary().first().filter { it.sourceId == sourceId }
            _entries.value = novels.map { MigrateEntry(it.id, it.title, it.coverUrl) }
            _checked.value = novels.map { it.id }.toSet()
        }
    }

    fun toggleChecked(novelId: Long) {
        val current = _checked.value
        _checked.value = if (novelId in current) current - novelId else current + novelId
    }

    fun setAllChecked(checkAll: Boolean) {
        _checked.value = if (checkAll) _entries.value.orEmpty().map { it.id }.toSet() else emptySet()
    }

    // --- step 2: what carries over ---

    private val _options = MutableStateFlow(MigrationOptions())
    val options: StateFlow<MigrationOptions> = _options.asStateFlow()

    fun setOptions(options: MigrationOptions) { _options.value = options }

    // --- step 3: where to look ---

    /**
     * Only the active section's sources are offered: a novel migrated onto a
     * manga source would be unreadable, so those targets are never a valid
     * choice rather than merely a bad one.
     */
    val availableSources: StateFlow<List<LibrarySourceUsage>> = combine(
        sourceManager.sources,
        container.sectionRepository.activeSection,
    ) { sources, active ->
        sources
            .filter { sectionOf(it) == active }
            .map { LibrarySourceUsage(sourceId = it.id, sourceName = it.name, count = 0) }
            .sortedBy { it.sourceName.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Sources to search. Null means "every source in this section" — distinct
     * from an empty set, which means the user deselected everything and should
     * get no results rather than silently falling back to all.
     */
    private val _targetSources = MutableStateFlow<Set<Long>?>(null)
    val targetSources: StateFlow<Set<Long>?> = _targetSources.asStateFlow()

    fun useAllSources() { _targetSources.value = null }

    fun toggleTargetSource(id: Long) {
        val current = _targetSources.value ?: availableSources.value.map { it.sourceId }.toSet()
        _targetSources.value = if (id in current) current - id else current + id
    }

    fun isSourceSelected(id: Long): Boolean = _targetSources.value?.contains(id) ?: true

    // --- step 4: review and commit ---

    private val _searches = MutableStateFlow<List<MigrationSearch>>(emptyList())
    val searches: StateFlow<List<MigrationSearch>> = _searches.asStateFlow()

    private val _selected = MutableStateFlow<Map<Long, MigrationCandidate>>(emptyMap())
    val selected: StateFlow<Map<Long, MigrationCandidate>> = _selected.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    fun search(novelIds: List<Long>) {
        if (_searching.value) return
        _searching.value = true
        _done.value = false
        _searches.value = emptyList()
        viewModelScope.launch {
            val results = mutableListOf<MigrationSearch>()
            // "All" still means all *in this section*; the manager would
            // otherwise fall back to every registered source.
            val active = container.sectionRepository.activeSection.first()
            val targets = _targetSources.value
                ?: sourceManager.catalogueSources()
                    .filter { sectionOf(it) == active }
                    .map { it.id }
                    .toSet()
            novelIds.forEachIndexed { index, id ->
                _progress.value = "Searching ${index + 1} / ${novelIds.size}"
                repo.getNovel(id)?.let { novel ->
                    results += migrations.findCandidates(
                        novel = novel,
                        targetSourceIds = targets,
                    )
                }
                _searches.value = results.toList()
            }
            // Pre-select the best candidate so the common case is one tap.
            _selected.value = results.mapNotNull { search ->
                search.candidates.firstOrNull()?.let { search.novel.id to it }
            }.toMap()
            _progress.value = null
            _searching.value = false
        }
    }

    fun choose(novelId: Long, candidate: MigrationCandidate) {
        _selected.value = _selected.value + (novelId to candidate)
    }

    fun skip(novelId: Long) {
        _selected.value = _selected.value - novelId
    }

    fun migrateSelected() {
        val choices = _selected.value
        if (choices.isEmpty()) return
        _searching.value = true
        val options = _options.value
        viewModelScope.launch {
            choices.entries.forEachIndexed { index, (novelId, candidate) ->
                _progress.value = "Migrating ${index + 1} / ${choices.size}"
                repo.getNovel(novelId)?.let { migrations.migrate(it, candidate, options) }
            }
            _progress.value = null
            _searching.value = false
            _done.value = true
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val app = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MigrationFlowViewModel(app) as T
            }
        }
    }
}
