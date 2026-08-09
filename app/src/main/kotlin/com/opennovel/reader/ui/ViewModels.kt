package com.opennovel.reader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.opennovel.reader.data.AppContainer
import com.opennovel.reader.data.LibraryRepository
import com.opennovel.reader.data.ReaderSettings
import com.opennovel.reader.data.SettingsRepository
import com.opennovel.reader.data.ThemeMode
import com.opennovel.reader.data.db.CategoryEntity
import com.opennovel.reader.data.db.ChapterEntity
import com.opennovel.reader.data.db.HistoryWithNovel
import com.opennovel.reader.data.db.NovelEntity
import com.opennovel.reader.download.Downloader
import com.opennovel.reader.source.SourceManager
import com.opennovel.reader.source.model.ChapterText
import com.opennovel.reader.source.model.SNovel
import com.opennovel.reader.tts.OcrScript
import com.opennovel.reader.tts.TtsManager
import com.opennovel.reader.tts.TtsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Factory that builds every ViewModel from the app container. */
class VmFactory(private val c: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(LibraryViewModel::class.java) ->
            LibraryViewModel(c.libraryRepository, c.settingsRepository)
        modelClass.isAssignableFrom(HistoryViewModel::class.java) ->
            HistoryViewModel(c.libraryRepository)
        modelClass.isAssignableFrom(UpdatesViewModel::class.java) ->
            UpdatesViewModel(c.libraryRepository, c.downloader)
        modelClass.isAssignableFrom(DownloadsViewModel::class.java) ->
            DownloadsViewModel(c.libraryRepository, c.downloader)
        modelClass.isAssignableFrom(MigrationViewModel::class.java) ->
            MigrationViewModel(c.libraryRepository, c.migrationManager)
        modelClass.isAssignableFrom(BrowseViewModel::class.java) ->
            BrowseViewModel(c.sourceManager, c.libraryRepository)
        modelClass.isAssignableFrom(NovelDetailViewModel::class.java) ->
            NovelDetailViewModel(c.libraryRepository, c.downloader)
        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(c.settingsRepository, c.appContext, c.translationManager)
        modelClass.isAssignableFrom(BackupViewModel::class.java) ->
            BackupViewModel(c.backupManager)
        modelClass.isAssignableFrom(ExtensionsViewModel::class.java) ->
            ExtensionsViewModel(c.extensionManager, c.extensionLoaders, c.sourceManager)
        modelClass.isAssignableFrom(ReaderViewModel::class.java) ->
            ReaderViewModel(c.libraryRepository, c.sourceManager, c.downloader, c.ttsManager, c.mangaPageOcr, c.translationManager, c.settingsRepository)
        else -> error("Unknown ViewModel ${modelClass.name}")
    } as T
}

/** How the library grid is ordered. */
enum class LibrarySort(val label: String) {
    TITLE("Title"),
    RECENTLY_ADDED("Recently added"),
}

/** Sentinel category id for the "Default" tab (novels in no user category). */
const val DEFAULT_CATEGORY_ID = 0L

/**
 * Tri-state library filter, as Mihon uses: a filter can be off, require the
 * property, or exclude it. Two booleans can't express "show only entries with
 * *no* downloads", which is exactly what you want when freeing space.
 */
enum class FilterState { IGNORED, INCLUDED, EXCLUDED;

    /** Cycles ignored → included → excluded → ignored on each tap. */
    fun next(): FilterState = when (this) {
        IGNORED -> INCLUDED
        INCLUDED -> EXCLUDED
        EXCLUDED -> IGNORED
    }

    /** Applies this filter to whether an entry has the property. */
    fun matches(has: Boolean): Boolean = when (this) {
        IGNORED -> true
        INCLUDED -> has
        EXCLUDED -> !has
    }
}

/** The library filter set, mirroring Mihon's filter sheet. */
data class LibraryFilters(
    val downloaded: FilterState = FilterState.IGNORED,
    val unread: FilterState = FilterState.IGNORED,
    val started: FilterState = FilterState.IGNORED,
) {
    val active: Int
        get() = listOf(downloaded, unread, started).count { it != FilterState.IGNORED }
}

class LibraryViewModel(
    private val repo: LibraryRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<ReaderSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())

    /** Unread/downloaded tallies keyed by novel id, for cover badges. */
    val counts: StateFlow<Map<Long, com.opennovel.reader.data.db.NovelCounts>> =
        repo.observeNovelCounts()
            .map { list -> list.associateBy { it.novelId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _sort = MutableStateFlow(LibrarySort.TITLE)
    val sort: StateFlow<LibrarySort> = _sort.asStateFlow()

    /** Selected category tab. [DEFAULT_CATEGORY_ID] = uncategorized "Default" shelf. */
    private val _selectedCategory = MutableStateFlow(DEFAULT_CATEGORY_ID)
    val selectedCategory: StateFlow<Long> = _selectedCategory.asStateFlow()

    val categories: StateFlow<List<CategoryEntity>> =
        repo.observeCategories()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val assignments = repo.observeCategoryAssignments()

    private val _filters = MutableStateFlow(LibraryFilters())
    val filters: StateFlow<LibraryFilters> = _filters.asStateFlow()

    /**
     * Category + search narrowing. Split from the filter/sort stage below
     * because `combine` tops out at five flows, and splitting also means a
     * filter change doesn't re-run category matching.
     */
    private val scopedLibrary =
        combine(repo.observeLibrary(), assignments, _query, _selectedCategory) { novels, refs, query, categoryId ->
            val byCategory = if (categoryId == DEFAULT_CATEGORY_ID) {
                val assignedNovelIds = refs.map { it.novelId }.toSet()
                novels.filter { it.id !in assignedNovelIds }
            } else {
                val idsInCategory = refs.filter { it.categoryId == categoryId }.map { it.novelId }.toSet()
                novels.filter { it.id in idsInCategory }
            }
            if (query.isBlank()) byCategory
            else byCategory.filter { it.title.contains(query.trim(), ignoreCase = true) }
        }

    /** Library after tri-state filters, ordered by the chosen sort. */
    val library: StateFlow<List<NovelEntity>> =
        combine(scopedLibrary, repo.observeNovelCounts(), _filters, _sort) { novels, countList, filters, sort ->
            val countsById = countList.associateBy { it.novelId }
            val filtered = novels.filter { novel ->
                val c = countsById[novel.id]
                // No chapter rows yet means nothing is downloaded, unread or
                // started — treat as false rather than hiding the entry outright.
                filters.downloaded.matches((c?.downloaded ?: 0) > 0) &&
                    filters.unread.matches((c?.unread ?: 0) > 0) &&
                    filters.started.matches(c?.started == true)
            }
            when (sort) {
                LibrarySort.TITLE -> filtered.sortedBy { it.title.lowercase() }
                LibrarySort.RECENTLY_ADDED -> filtered.sortedByDescending { it.dateAdded }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun cycleDownloadedFilter() { _filters.value = _filters.value.copy(downloaded = _filters.value.downloaded.next()) }
    fun cycleUnreadFilter() { _filters.value = _filters.value.copy(unread = _filters.value.unread.next()) }
    fun cycleStartedFilter() { _filters.value = _filters.value.copy(started = _filters.value.started.next()) }
    fun clearFilters() { _filters.value = LibraryFilters() }

    fun setQuery(value: String) { _query.value = value }

    fun setSort(value: LibrarySort) { _sort.value = value }

    fun selectCategory(id: Long) { _selectedCategory.value = id }

    // --- multi-select (batch migration) ---

    /** Ids picked in selection mode; empty means normal browsing. */
    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    fun toggleSelection(novelId: Long) {
        _selection.value = if (novelId in _selection.value) {
            _selection.value - novelId
        } else {
            _selection.value + novelId
        }
    }

    fun clearSelection() { _selection.value = emptySet() }

    fun selectAllVisible() { _selection.value = library.value.map { it.id }.toSet() }

    fun setLibraryDisplayMode(mode: com.opennovel.reader.data.LibraryDisplayMode) =
        viewModelScope.launch { settingsRepository.setLibraryDisplayMode(mode) }

    // --- category management ---

    fun createCategory(name: String) = viewModelScope.launch { repo.createCategory(name) }

    fun renameCategory(id: Long, name: String) = viewModelScope.launch { repo.renameCategory(id, name) }

    fun deleteCategory(id: Long) = viewModelScope.launch {
        repo.deleteCategory(id)
        if (_selectedCategory.value == id) _selectedCategory.value = DEFAULT_CATEGORY_ID
    }

    /** Current category ids for a novel, for pre-checking the assign dialog. */
    fun categoryIdsForNovel(novelId: Long, onLoaded: (Set<Long>) -> Unit) {
        viewModelScope.launch { onLoaded(repo.categoryIdsForNovel(novelId).toSet()) }
    }

    fun setNovelCategories(novelId: Long, categoryIds: Set<Long>) =
        viewModelScope.launch { repo.setNovelCategories(novelId, categoryIds) }

    /** Resolve the chapter to open for a tapped novel, then invoke [onResolved]. */
    fun openNovel(novelId: Long, onResolved: (Long?) -> Unit) {
        viewModelScope.launch { onResolved(repo.resumeChapterId(novelId)) }
    }
}

/**
 * Drives source migration for one or many library entries.
 *
 * Each selected entry searches independently and is previewed before anything
 * changes — migrating silently would risk destroying reading progress on a bad
 * title match, so confirmation is always the user's.
 */
class MigrationViewModel(
    private val repo: LibraryRepository,
    private val migrations: com.opennovel.reader.migration.MigrationManager,
) : ViewModel() {

    private val _searches =
        MutableStateFlow<List<com.opennovel.reader.migration.MigrationSearch>>(emptyList())
    val searches: StateFlow<List<com.opennovel.reader.migration.MigrationSearch>> = _searches.asStateFlow()

    /** Candidate chosen per novel id; nothing migrates until one is picked. */
    private val _selected = MutableStateFlow<Map<Long, com.opennovel.reader.migration.MigrationCandidate>>(emptyMap())
    val selected: StateFlow<Map<Long, com.opennovel.reader.migration.MigrationCandidate>> = _selected.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    /** Searches every other source for each selected entry. */
    fun search(novelIds: List<Long>) {
        if (_searching.value) return
        _searching.value = true
        _done.value = false
        viewModelScope.launch {
            val results = mutableListOf<com.opennovel.reader.migration.MigrationSearch>()
            novelIds.forEachIndexed { index, id ->
                _progress.value = "Searching ${index + 1} / ${novelIds.size}"
                repo.getNovel(id)?.let { novel ->
                    results += migrations.findCandidates(novel)
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

    fun choose(novelId: Long, candidate: com.opennovel.reader.migration.MigrationCandidate) {
        _selected.value = _selected.value + (novelId to candidate)
    }

    fun skip(novelId: Long) {
        _selected.value = _selected.value - novelId
    }

    /** Migrates every entry that still has a chosen candidate. */
    fun migrateSelected(onFinished: (migrated: Int) -> Unit = {}) {
        val choices = _selected.value
        if (choices.isEmpty()) return
        _searching.value = true
        viewModelScope.launch {
            var migrated = 0
            choices.entries.forEachIndexed { index, (novelId, candidate) ->
                _progress.value = "Migrating ${index + 1} / ${choices.size}"
                val novel = repo.getNovel(novelId)
                if (novel != null && migrations.migrate(novel, candidate).isSuccess) migrated++
            }
            _progress.value = null
            _searching.value = false
            _done.value = true
            onFinished(migrated)
        }
    }

    fun reset() {
        _searches.value = emptyList()
        _selected.value = emptyMap()
        _done.value = false
    }
}

/** Newest chapters across the library, with a pull-to-refresh sweep of all sources. */
class UpdatesViewModel(
    private val repo: LibraryRepository,
    private val downloader: Downloader,
) : ViewModel() {

    val updates: StateFlow<List<com.opennovel.reader.data.db.ChapterWithNovel>> =
        repo.observeRecentChapters()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** "12 / 40" while a library sweep runs, so long refreshes show progress. */
    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            repo.refreshLibrary { done, total -> _progress.value = "$done / $total" }
            _progress.value = null
            _refreshing.value = false
        }
    }

    fun markRead(chapterId: Long, read: Boolean) =
        viewModelScope.launch { repo.markRead(chapterId, read, if (read) 1f else 0f) }

    fun download(chapterId: Long) = viewModelScope.launch { downloader.download(chapterId) }
}

/** Download manager: what's queued, running, and already stored offline. */
class DownloadsViewModel(
    private val repo: LibraryRepository,
    private val downloader: Downloader,
) : ViewModel() {

    val downloaded: StateFlow<List<com.opennovel.reader.data.db.ChapterWithNovel>> =
        repo.observeDownloaded()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Live per-chapter state (RUNNING/DONE/FAILED) straight from the downloader. */
    val progress: StateFlow<Map<Long, com.opennovel.reader.download.DownloadState>> = downloader.progress

    fun delete(chapterId: Long) = viewModelScope.launch { downloader.delete(chapterId) }

    /** Queues every not-yet-downloaded chapter of a novel. */
    fun downloadAll(novelId: Long) = viewModelScope.launch {
        downloader.enqueue(repo.undownloadedChapterIds(novelId))
    }

    fun retry(chapterId: Long) = viewModelScope.launch { downloader.download(chapterId) }
}

class HistoryViewModel(private val repo: LibraryRepository) : ViewModel() {

    val history: StateFlow<List<HistoryWithNovel>> =
        repo.observeHistory().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Resolve the resume chapter for a history entry, then invoke [onResolved]. */
    fun resume(novelId: Long, onResolved: (Long?) -> Unit) {
        viewModelScope.launch { onResolved(repo.resumeChapterId(novelId)) }
    }

    fun remove(novelId: Long) = viewModelScope.launch { repo.removeHistory(novelId) }

    fun clearAll() = viewModelScope.launch { repo.clearHistory() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NovelDetailViewModel(
    private val repo: LibraryRepository,
    private val downloader: Downloader,
) : ViewModel() {

    private val novelId = MutableStateFlow<Long?>(null)

    val novel: StateFlow<NovelEntity?> =
        novelId.flatMapLatest { id -> if (id == null) flowOf(null) else repo.observeNovel(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val chapters: StateFlow<List<ChapterEntity>> =
        novelId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.observeChapters(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun load(id: Long) {
        if (novelId.value == id) return
        novelId.value = id
        refresh()
    }

    fun refresh() {
        val id = novelId.value ?: return
        _refreshing.value = true
        viewModelScope.launch {
            runCatching { repo.refreshChapters(id) }
            _refreshing.value = false
        }
    }

    fun toggleLibrary() {
        val n = novel.value ?: return
        viewModelScope.launch { repo.addToLibrary(n.id, !n.inLibrary) }
    }

    fun markRead(chapterId: Long, read: Boolean) =
        viewModelScope.launch { repo.markRead(chapterId, read, if (read) 1f else 0f) }

    fun download(chapterId: Long) = viewModelScope.launch { downloader.download(chapterId) }

    /** Resolve the resume/first chapter for the "Continue" button. */
    fun resume(onResolved: (Long?) -> Unit) {
        val id = novelId.value ?: return
        viewModelScope.launch { onResolved(repo.resumeChapterId(id)) }
    }
}

class BrowseViewModel(
    private val sourceManager: SourceManager,
    private val repo: LibraryRepository,
) : ViewModel() {
    private val _results = MutableStateFlow<List<SNovel>>(emptyList())
    val results: StateFlow<List<SNovel>> = _results.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val sources = sourceManager.sources

    var activeSourceId: Long? = sourceManager.catalogueSources().firstOrNull()?.id
        private set

    fun selectSource(id: Long) { activeSourceId = id; loadPopular() }

    fun loadPopular() {
        val source = activeSourceId?.let { sourceManager.get(it) } ?: return
        _loading.value = true
        viewModelScope.launch {
            runCatching { source.getPopularNovels(1) }
                .onSuccess { _results.value = it.novels }
            _loading.value = false
        }
    }

    fun search(query: String) {
        val source = activeSourceId?.let { sourceManager.get(it) } ?: return
        _loading.value = true
        viewModelScope.launch {
            runCatching { source.searchNovels(query, 1) }
                .onSuccess { _results.value = it.novels }
            _loading.value = false
        }
    }

    /** Adds a browsed novel to the library and returns its local id. */
    suspend fun addToLibrary(novel: SNovel): Long? {
        val sourceId = activeSourceId ?: return null
        val id = repo.cacheNovel(sourceId, novel)
        repo.addToLibrary(id, true)
        repo.refreshChapters(id)
        return id
    }

    /** Caches a browsed novel locally (without adding to library) so its detail
     *  screen can be opened; returns the local id. */
    suspend fun cacheForDetails(novel: SNovel): Long? {
        val sourceId = activeSourceId ?: return null
        return repo.cacheNovel(sourceId, novel)
    }

    // --- global search: one query fanned out across every installed source ---

    private val _global = MutableStateFlow<List<SourceSearchResult>>(emptyList())
    val global: StateFlow<List<SourceSearchResult>> = _global.asStateFlow()

    private val _globalMode = MutableStateFlow(false)
    val globalMode: StateFlow<Boolean> = _globalMode.asStateFlow()

    fun setGlobalMode(on: Boolean) { _globalMode.value = on; if (!on) _global.value = emptyList() }

    /** Search every catalogue source concurrently; each section updates as it returns. */
    fun globalSearch(query: String) {
        if (query.isBlank()) return
        val sources = sourceManager.catalogueSources()
        _global.value = sources.map { SourceSearchResult(it.id, it.name, loading = true) }
        sources.forEach { source ->
            viewModelScope.launch {
                val outcome = runCatching { source.searchNovels(query, 1) }
                _global.value = _global.value.map { row ->
                    if (row.sourceId != source.id) row
                    else outcome.fold(
                        { row.copy(novels = it.novels, loading = false) },
                        { row.copy(loading = false, error = it.message ?: "Failed") },
                    )
                }
            }
        }
    }

    /** Cache a global-search result under its own source (not the active one). */
    suspend fun cacheForDetails(sourceId: Long, novel: SNovel): Long =
        repo.cacheNovel(sourceId, novel)

    /** Add an already-cached novel (by local id) to the library and pull chapters. */
    suspend fun addExistingToLibrary(novelId: Long) {
        repo.addToLibrary(novelId, true)
        repo.refreshChapters(novelId)
    }
}

/** One source's slice of a global search: its results plus loading/error state. */
data class SourceSearchResult(
    val sourceId: Long,
    val sourceName: String,
    val novels: List<SNovel> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Backs the Extensions screen: shows what's installed across every ecosystem,
 * browses the LNReader plugin repository, and installs plugins.
 *
 * APK ecosystems (Mihon/Manatan/IReader) are installed through the system
 * package manager, so they can only be listed here — not installed in-app.
 */
class ExtensionsViewModel(
    private val extensionManager: com.opennovel.reader.extension.ExtensionManager,
    private val loaders: List<com.opennovel.reader.extension.ExtensionLoader>,
    private val sourceManager: SourceManager,
) : ViewModel() {

    private val _installed = MutableStateFlow<List<com.opennovel.reader.extension.ExtensionInfo>>(emptyList())
    val installed: StateFlow<List<com.opennovel.reader.extension.ExtensionInfo>> = _installed.asStateFlow()

    private val _available = MutableStateFlow<List<com.opennovel.reader.extension.ExtensionInfo>>(emptyList())
    val available: StateFlow<List<com.opennovel.reader.extension.ExtensionInfo>> = _available.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val lnLoader
        get() = loaders.filterIsInstance<com.opennovel.reader.extension.lnreader.LNReaderPluginLoader>().firstOrNull()

    fun refreshInstalled() {
        _busy.value = true
        viewModelScope.launch {
            extensionManager.loadInstalled()
            _installed.value = extensionManager.installed.value
            _busy.value = false
        }
    }

    /** Loads the LNReader plugin catalogue. */
    fun browseRepository() {
        val loader = lnLoader ?: return
        _busy.value = true
        viewModelScope.launch {
            _available.value = runCatching {
                loader.listAvailable(
                    com.opennovel.reader.extension.lnreader.LNReaderPluginLoader.DEFAULT_REPO,
                )
            }.getOrDefault(emptyList())
            _busy.value = false
            if (_available.value.isEmpty()) _status.value = "Could not load the plugin repository"
        }
    }

    fun install(info: com.opennovel.reader.extension.ExtensionInfo) {
        val loader = lnLoader ?: return
        _busy.value = true
        viewModelScope.launch {
            val ok = runCatching { loader.install(info) }.getOrDefault(false)
            if (ok) {
                runCatching { loader.load(info) }.getOrDefault(emptyList())
                    .forEach(sourceManager::register)
                _available.value = _available.value.map {
                    if (it.pkgId == info.pkgId) it.copy(installed = true) else it
                }
                refreshInstalled()
            }
            _status.value = if (ok) "Installed ${info.name}" else "Failed to install ${info.name}"
            _busy.value = false
        }
    }

    fun clearStatus() { _status.value = null }
}

class BackupViewModel(private val backup: com.opennovel.reader.backup.BackupManager) : ViewModel() {
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun exportTo(out: java.io.OutputStream) {
        _busy.value = true
        viewModelScope.launch {
            _status.value = runCatching { backup.export(out) }
                .fold({ "Backup created" }, { "Backup failed: ${it.message}" })
            _busy.value = false
        }
    }

    fun importFrom(input: java.io.InputStream, isManatan: Boolean) {
        if (isManatan) {
            _status.value = "Manatan backups aren't supported yet — use a Mihon/Tachiyomi .tachibk file."
            return
        }
        _busy.value = true
        viewModelScope.launch {
            _status.value = runCatching { backup.import(input) }
                .fold(
                    { "Restored ${it.novels} novels, ${it.chapters} chapters, ${it.categories} categories" },
                    { "Restore failed: ${it.message}" },
                )
            _busy.value = false
        }
    }

    fun clearStatus() { _status.value = null }
}

class SettingsViewModel(
    private val repo: SettingsRepository,
    private val appContext: android.content.Context,
    private val translator: com.opennovel.reader.tts.TranslationManager,
) : ViewModel() {

    /** Progress text while translation packs pre-download, else null. */
    private val _packStatus = MutableStateFlow<String?>(null)
    val packStatus: StateFlow<String?> = _packStatus.asStateFlow()

    /**
     * Fetches every translation model up front.
     *
     * ML Kit has no bundled translation artifact — models are download-only — so
     * this is the closest equivalent to shipping them: pull them once, then
     * translation works offline and instantly.
     */
    fun downloadTranslationPacks() {
        viewModelScope.launch {
            val target = repo.settings.first().translateTarget.code
            _packStatus.value = "Starting…"
            val ready = translator.preloadAll(target, requireWifi = false) { done, total, language ->
                _packStatus.value = "Downloading $language ($done/$total)"
            }
            _packStatus.value = if (ready > 0) "$ready language packs ready" else "Download failed"
        }
    }

    /** Re-applies WorkManager scheduling whenever the cadence changes. */
    private fun rescheduleUpdates() {
        viewModelScope.launch {
            runCatching {
                com.opennovel.reader.update.UpdateScheduler.apply(appContext, repo)
            }
        }
    }
    val settings: StateFlow<ReaderSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())

    fun setFontScale(v: Float) = viewModelScope.launch { repo.setFontScale(v) }
    fun setLineSpacing(v: Float) = viewModelScope.launch { repo.setLineSpacing(v) }
    fun setTheme(v: ThemeMode) = viewModelScope.launch { repo.setThemeMode(v) }
    fun setFontFamily(v: String) = viewModelScope.launch { repo.setFontFamily(v) }
    fun setTtsSpeed(v: Float) = viewModelScope.launch { repo.setTtsSpeed(v) }
    fun setTtsPitch(v: Float) = viewModelScope.launch { repo.setTtsPitch(v) }
    fun setKeepScreenOn(v: Boolean) = viewModelScope.launch { repo.setKeepScreenOn(v) }
    fun setReadingMode(v: com.opennovel.reader.data.ReadingMode) = viewModelScope.launch { repo.setReadingMode(v) }
    fun setOcrScript(v: com.opennovel.reader.data.OcrScriptSetting) = viewModelScope.launch { repo.setOcrScript(v) }
    fun setTtsLanguage(v: com.opennovel.reader.data.SpeechLanguage) = viewModelScope.launch { repo.setTtsLanguage(v) }
    fun setTranslateEnabled(v: Boolean) = viewModelScope.launch { repo.setTranslateEnabled(v) }
    fun setTranslateTarget(v: com.opennovel.reader.data.TranslateLanguage) = viewModelScope.launch { repo.setTranslateTarget(v) }
    fun setUpdateSchedule(v: com.opennovel.reader.data.UpdateSchedule) = viewModelScope.launch { repo.setUpdateSchedule(v); rescheduleUpdates() }
    fun setUpdateTime(hour: Int, minute: Int) = viewModelScope.launch { repo.setUpdateTime(hour, minute); rescheduleUpdates() }
    fun setUpdateDayOfWeek(v: Int) = viewModelScope.launch { repo.setUpdateDayOfWeek(v); rescheduleUpdates() }
    fun setUpdateDayOfMonth(v: Int) = viewModelScope.launch { repo.setUpdateDayOfMonth(v); rescheduleUpdates() }
    fun setUpdateOnWifiOnly(v: Boolean) = viewModelScope.launch { repo.setUpdateOnWifiOnly(v); rescheduleUpdates() }
}

class ReaderViewModel(
    private val repo: LibraryRepository,
    private val sourceManager: SourceManager,
    private val downloader: Downloader,
    val tts: TtsManager,
    private val ocr: com.opennovel.reader.tts.MangaPageOcr,
    private val translator: com.opennovel.reader.tts.TranslationManager,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    // Mirrors of the Settings screen controls, so the in-reader sheet can adjust
    // type and layout while the result is visible behind it.
    fun setFontScale(v: Float) = viewModelScope.launch { settingsRepo.setFontScale(v) }
    fun setLineSpacing(v: Float) = viewModelScope.launch { settingsRepo.setLineSpacing(v) }
    fun setFontFamily(v: String) = viewModelScope.launch { settingsRepo.setFontFamily(v) }
    fun setThemeMode(v: ThemeMode) = viewModelScope.launch { settingsRepo.setThemeMode(v) }
    fun setKeepScreenOn(v: Boolean) = viewModelScope.launch { settingsRepo.setKeepScreenOn(v) }
    fun setReadingMode(v: com.opennovel.reader.data.ReadingMode) =
        viewModelScope.launch { settingsRepo.setReadingMode(v) }

    private val _content = MutableStateFlow<ChapterText?>(null)
    val content: StateFlow<ChapterText?> = _content.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val ttsState: StateFlow<TtsState> = tts.state

    val settings = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())

    /** Page images when the chapter comes from a manga source; empty for novels. */
    private val _pageUrls = MutableStateFlow<List<String>>(emptyList())
    val pageUrls: StateFlow<List<String>> = _pageUrls.asStateFlow()

    /** True while manga pages are being OCR'd for narration. */
    private val _ocrRunning = MutableStateFlow(false)
    val ocrRunning: StateFlow<Boolean> = _ocrRunning.asStateFlow()

    /** True while text is being translated (may include a model download). */
    private val _translating = MutableStateFlow(false)
    val translating: StateFlow<Boolean> = _translating.asStateFlow()

    private var current: ChapterEntity? = null

    fun load(chapterId: Long) {
        _loading.value = true; _error.value = null
        _pageUrls.value = emptyList()
        viewModelScope.launch {
            val chapter = repo.getChapter(chapterId)
            current = chapter
            if (chapter == null) { _error.value = "Chapter not found"; _loading.value = false; return@launch }
            val text = if (chapter.downloaded && chapter.downloadPath != null) {
                downloader.readLocal(chapter.downloadPath)?.let { ChapterText(it.split("\n\n")) }
            } else {
                repo.fetchChapterText(chapter)
            }
            _content.value = text

            // Text sources yield paragraphs; manga sources yield page images instead.
            if (text == null || text.paragraphs.isEmpty()) {
                val pages = repo.fetchPageUrls(chapter)
                _pageUrls.value = pages
                if (pages.isEmpty() && text == null) _error.value = "Could not load chapter"
            }
            _loading.value = false
        }
    }

    fun downloadCurrent() {
        val c = current ?: return
        viewModelScope.launch { downloader.download(c.id) }
    }

    fun saveProgress(offset: Float) {
        val c = current ?: return
        viewModelScope.launch { repo.saveProgress(c.novelId, c.id, offset) }
    }

    /**
     * Narrates the chapter. Novels speak their paragraphs directly; manga has no
     * source text, so pages are OCR'd first. When translation is on, recognised
     * (or novel) text is translated before it is shown and spoken, so the
     * narrator's language matches what's on screen.
     *
     * Results are written back to [_content] so replaying doesn't repeat the
     * expensive OCR/translation work.
     */
    fun startTts(speed: Float, pitch: Float, voice: String) {
        viewModelScope.launch {
            val s = settings.value
            var paras = _content.value?.paragraphs.orEmpty()
            val script = s.ocrScript.toOcrScript()

            if (paras.isEmpty()) {
                val pages = _pageUrls.value
                if (pages.isEmpty()) return@launch
                _ocrRunning.value = true
                paras = runCatching { ocr.readChapter(pages, script) }.getOrDefault(emptyList())
                _ocrRunning.value = false
                if (paras.isEmpty()) {
                    _error.value = "No text recognised on these pages"
                    return@launch
                }
                _content.value = ChapterText(paras)
            }

            if (s.translateEnabled) {
                val translated = translateLines(paras, script, s.translateTarget.code)
                if (translated != null) {
                    paras = translated
                    _content.value = ChapterText(paras)
                }
            }

            val speakable = paras
            tts.init {
                tts.configure(speed, pitch, voice, s.ttsLanguage.tag)
                tts.speak(speakable, tts.state.value.index)
            }
        }
    }

    /** Translates the current chapter in place, without starting narration. */
    fun translateCurrent() {
        viewModelScope.launch {
            val s = settings.value
            var paras = _content.value?.paragraphs.orEmpty()
            val script = s.ocrScript.toOcrScript()

            if (paras.isEmpty()) {
                val pages = _pageUrls.value
                if (pages.isEmpty()) return@launch
                _ocrRunning.value = true
                paras = runCatching { ocr.readChapter(pages, script) }.getOrDefault(emptyList())
                _ocrRunning.value = false
                if (paras.isEmpty()) { _error.value = "No text recognised on these pages"; return@launch }
            }
            val translated = translateLines(paras, script, s.translateTarget.code)
            _content.value = ChapterText(translated ?: paras)
        }
    }

    /** Downloads the model pair if needed, then translates. Null means unavailable. */
    private suspend fun translateLines(
        lines: List<String>,
        script: OcrScript,
        targetCode: String,
    ): List<String>? {
        _translating.value = true
        val source = translator.sourceFor(script)
        val ready = translator.ensureModel(source, targetCode)
        val result = if (ready) {
            translator.translate(lines, source, targetCode)
        } else {
            _error.value = "Translation model unavailable — connect to Wi-Fi to download it"
            null
        }
        _translating.value = false
        return result
    }

    private fun com.opennovel.reader.data.OcrScriptSetting.toOcrScript(): OcrScript = when (this) {
        com.opennovel.reader.data.OcrScriptSetting.LATIN -> OcrScript.LATIN
        com.opennovel.reader.data.OcrScriptSetting.JAPANESE -> OcrScript.JAPANESE
        com.opennovel.reader.data.OcrScriptSetting.KOREAN -> OcrScript.KOREAN
        com.opennovel.reader.data.OcrScriptSetting.CHINESE -> OcrScript.CHINESE
    }
    fun pauseTts() = tts.pause()
    fun resumeTts() = tts.resume()
    fun stopTts() = tts.stop()

    override fun onCleared() { tts.stop() }
}



