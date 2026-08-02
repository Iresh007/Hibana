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
import com.opennovel.reader.tts.TtsManager
import com.opennovel.reader.tts.TtsState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Factory that builds every ViewModel from the app container. */
class VmFactory(private val c: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(LibraryViewModel::class.java) ->
            LibraryViewModel(c.libraryRepository)
        modelClass.isAssignableFrom(HistoryViewModel::class.java) ->
            HistoryViewModel(c.libraryRepository)
        modelClass.isAssignableFrom(BrowseViewModel::class.java) ->
            BrowseViewModel(c.sourceManager, c.libraryRepository)
        modelClass.isAssignableFrom(NovelDetailViewModel::class.java) ->
            NovelDetailViewModel(c.libraryRepository, c.downloader)
        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(c.settingsRepository)
        modelClass.isAssignableFrom(BackupViewModel::class.java) ->
            BackupViewModel(c.backupManager)
        modelClass.isAssignableFrom(ReaderViewModel::class.java) ->
            ReaderViewModel(c.libraryRepository, c.sourceManager, c.downloader, c.ttsManager, c.settingsRepository)
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

class LibraryViewModel(private val repo: LibraryRepository) : ViewModel() {

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

    /** Library filtered by search query + selected category, ordered by the chosen sort. */
    val library: StateFlow<List<NovelEntity>> =
        combine(repo.observeLibrary(), assignments, _query, _sort, _selectedCategory) { novels, refs, query, sort, categoryId ->
            val byCategory = if (categoryId == DEFAULT_CATEGORY_ID) {
                val assignedNovelIds = refs.map { it.novelId }.toSet()
                novels.filter { it.id !in assignedNovelIds }
            } else {
                val idsInCategory = refs.filter { it.categoryId == categoryId }.map { it.novelId }.toSet()
                novels.filter { it.id in idsInCategory }
            }
            val filtered =
                if (query.isBlank()) byCategory
                else byCategory.filter { it.title.contains(query.trim(), ignoreCase = true) }
            when (sort) {
                LibrarySort.TITLE -> filtered.sortedBy { it.title.lowercase() }
                LibrarySort.RECENTLY_ADDED -> filtered.sortedByDescending { it.dateAdded }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setQuery(value: String) { _query.value = value }

    fun setSort(value: LibrarySort) { _sort.value = value }

    fun selectCategory(id: Long) { _selectedCategory.value = id }

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

class SettingsViewModel(private val repo: SettingsRepository) : ViewModel() {
    val settings: StateFlow<ReaderSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())

    fun setFontScale(v: Float) = viewModelScope.launch { repo.setFontScale(v) }
    fun setLineSpacing(v: Float) = viewModelScope.launch { repo.setLineSpacing(v) }
    fun setTheme(v: ThemeMode) = viewModelScope.launch { repo.setThemeMode(v) }
    fun setFontFamily(v: String) = viewModelScope.launch { repo.setFontFamily(v) }
    fun setTtsSpeed(v: Float) = viewModelScope.launch { repo.setTtsSpeed(v) }
    fun setTtsPitch(v: Float) = viewModelScope.launch { repo.setTtsPitch(v) }
    fun setKeepScreenOn(v: Boolean) = viewModelScope.launch { repo.setKeepScreenOn(v) }
}

class ReaderViewModel(
    private val repo: LibraryRepository,
    private val sourceManager: SourceManager,
    private val downloader: Downloader,
    val tts: TtsManager,
    settingsRepo: SettingsRepository,
) : ViewModel() {

    private val _content = MutableStateFlow<ChapterText?>(null)
    val content: StateFlow<ChapterText?> = _content.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val ttsState: StateFlow<TtsState> = tts.state

    val settings = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())

    private var current: ChapterEntity? = null

    fun load(chapterId: Long) {
        _loading.value = true; _error.value = null
        viewModelScope.launch {
            val chapter = repo.getChapter(chapterId)
            current = chapter
            if (chapter == null) { _error.value = "Chapter not found"; _loading.value = false; return@launch }
            val text = if (chapter.downloaded && chapter.downloadPath != null) {
                downloader.readLocal(chapter.downloadPath)?.let { ChapterText(it.split("\n\n")) }
            } else {
                repo.fetchChapterText(chapter)
            }
            if (text == null) _error.value = "Could not load chapter" else _content.value = text
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

    fun startTts(speed: Float, pitch: Float, voice: String) {
        val paras = _content.value?.paragraphs ?: return
        tts.init {
            tts.configure(speed, pitch, voice)
            tts.speak(paras, tts.state.value.index)
        }
    }
    fun pauseTts() = tts.pause()
    fun resumeTts() = tts.resume()
    fun stopTts() = tts.stop()

    override fun onCleared() { tts.stop() }
}
