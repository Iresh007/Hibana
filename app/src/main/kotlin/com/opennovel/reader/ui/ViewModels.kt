package com.opennovel.reader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.opennovel.reader.data.AppContainer
import com.opennovel.reader.data.LibraryRepository
import com.opennovel.reader.data.ReaderSettings
import com.opennovel.reader.data.SettingsRepository
import com.opennovel.reader.data.ThemeMode
import com.opennovel.reader.data.db.ChapterEntity
import com.opennovel.reader.data.db.HistoryWithNovel
import com.opennovel.reader.data.db.NovelEntity
import com.opennovel.reader.download.Downloader
import com.opennovel.reader.source.SourceManager
import com.opennovel.reader.source.model.ChapterText
import com.opennovel.reader.source.model.SNovel
import com.opennovel.reader.tts.TtsManager
import com.opennovel.reader.tts.TtsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(c.settingsRepository)
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

class LibraryViewModel(private val repo: LibraryRepository) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _sort = MutableStateFlow(LibrarySort.TITLE)
    val sort: StateFlow<LibrarySort> = _sort.asStateFlow()

    /** Library filtered by the search query and ordered by the chosen sort. */
    val library: StateFlow<List<NovelEntity>> =
        combine(repo.observeLibrary(), _query, _sort) { novels, query, sort ->
            val filtered =
                if (query.isBlank()) novels
                else novels.filter { it.title.contains(query.trim(), ignoreCase = true) }
            when (sort) {
                LibrarySort.TITLE -> filtered.sortedBy { it.title.lowercase() }
                LibrarySort.RECENTLY_ADDED -> filtered.sortedByDescending { it.dateAdded }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setQuery(value: String) { _query.value = value }

    fun setSort(value: LibrarySort) { _sort.value = value }

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
