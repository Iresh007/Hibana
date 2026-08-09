package com.opennovel.reader.data

import com.opennovel.reader.data.db.CategoryDao
import com.opennovel.reader.data.db.CategoryEntity
import com.opennovel.reader.data.db.ChapterDao
import com.opennovel.reader.data.db.ChapterEntity
import com.opennovel.reader.data.db.HistoryDao
import com.opennovel.reader.data.db.HistoryEntity
import com.opennovel.reader.data.db.HistoryWithNovel
import com.opennovel.reader.data.db.NovelCategoryCrossRef
import com.opennovel.reader.data.db.NovelDao
import com.opennovel.reader.data.db.NovelEntity
import com.opennovel.reader.source.SourceManager
import com.opennovel.reader.source.model.SChapter
import com.opennovel.reader.source.model.SNovel
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth over library + chapters. Coordinates the local Room
 * store with remote sources: fetching details/chapters, adding to library,
 * and persisting read/download state.
 */
class LibraryRepository(
    private val novelDao: NovelDao,
    private val chapterDao: ChapterDao,
    private val historyDao: HistoryDao,
    private val categoryDao: CategoryDao,
    private val sourceManager: SourceManager,
) {
    fun observeLibrary(): Flow<List<NovelEntity>> = novelDao.observeLibrary()

    // --- categories ---

    fun observeCategories(): Flow<List<CategoryEntity>> = categoryDao.observeCategories()

    /** All novel→category assignments, so the library can be grouped in one pass. */
    fun observeCategoryAssignments(): Flow<List<NovelCategoryCrossRef>> =
        categoryDao.observeAllAssignments()

    fun observeCategoryIdsForNovel(novelId: Long): Flow<List<Long>> =
        categoryDao.observeCategoryIdsForNovel(novelId)

    suspend fun categoryIdsForNovel(novelId: Long): List<Long> =
        categoryDao.categoryIdsForNovel(novelId)

    suspend fun createCategory(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        categoryDao.insert(CategoryEntity(name = trimmed, order = categoryDao.count()))
    }

    suspend fun renameCategory(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) categoryDao.rename(id, trimmed)
    }

    suspend fun deleteCategory(id: Long) = categoryDao.delete(id)

    /** Replaces a novel's category membership with exactly [categoryIds]. */
    suspend fun setNovelCategories(novelId: Long, categoryIds: Set<Long>) {
        categoryDao.clearAssignments(novelId)
        categoryIds.forEach { categoryDao.assign(NovelCategoryCrossRef(novelId, it)) }
    }

    // --- updates feed / downloads ---

    /** Newest chapters across the library — the Updates tab. */
    fun observeRecentChapters(): Flow<List<com.opennovel.reader.data.db.ChapterWithNovel>> =
        chapterDao.observeRecentChapters()

    /** Everything currently downloaded, for the download manager. */
    fun observeDownloaded(): Flow<List<com.opennovel.reader.data.db.ChapterWithNovel>> =
        chapterDao.observeDownloaded()

    suspend fun undownloadedChapterIds(novelId: Long): List<Long> =
        chapterDao.undownloadedIds(novelId)

    /**
     * Refreshes chapters for every library novel. Failures are per-novel so one
     * dead source can't abort the whole sweep; returns how many succeeded.
     */
    suspend fun refreshLibrary(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int {
        val novels = novelDao.getAllInLibrary()
        var ok = 0
        novels.forEachIndexed { index, novel ->
            if (runCatching { refreshChapters(novel.id) }.isSuccess) ok++
            onProgress(index + 1, novels.size)
        }
        return ok
    }

    /** Recently read novels (one row per novel, newest first). */
    fun observeHistory(): Flow<List<HistoryWithNovel>> = historyDao.observeHistory()

    suspend fun removeHistory(novelId: Long) = historyDao.deleteForNovel(novelId)

    suspend fun clearHistory() = historyDao.clear()

    fun observeNovel(id: Long): Flow<NovelEntity?> = novelDao.observeNovel(id)

    fun observeChapters(novelId: Long): Flow<List<ChapterEntity>> =
        chapterDao.observeChapters(novelId)

    suspend fun getChapter(id: Long): ChapterEntity? = chapterDao.getById(id)

    suspend fun getNovel(id: Long): NovelEntity? = novelDao.getById(id)

    /** The chapter to open when the user taps a library item: resume point or first. */
    suspend fun resumeChapterId(novelId: Long): Long? {
        val novel = novelDao.getById(novelId)
        return novel?.lastReadChapterId ?: chapterDao.firstChapterId(novelId)
    }

    /**
     * Page image URLs for a chapter, when the owning source is image-based
     * (Mihon/Manatan manga). Empty for text sources.
     */
    suspend fun fetchPageUrls(chapter: ChapterEntity): List<String> {
        val novel = novelDao.getById(chapter.novelId) ?: return emptyList()
        val source = sourceManager.get(novel.sourceId) as? com.opennovel.reader.source.ImageChapterSource
            ?: return emptyList()
        return runCatching { source.getPageUrls(chapter.url) }.getOrDefault(emptyList())
    }

    /** Resolves the owning source and fetches a chapter body from the network. */
    suspend fun fetchChapterText(chapter: ChapterEntity): com.opennovel.reader.source.model.ChapterText? {
        val novel = novelDao.getById(chapter.novelId) ?: return null
        val source = sourceManager.get(novel.sourceId) ?: return null
        return runCatching { source.getChapterText(chapter.url) }.getOrNull()
    }

    /** Ensures a source result exists locally; returns the local novel id. */
    suspend fun cacheNovel(sourceId: Long, novel: SNovel): Long {
        val existing = novelDao.findByUrl(sourceId, novel.url)
        val entity = (existing ?: NovelEntity(sourceId = sourceId, url = novel.url, title = novel.title)).copy(
            title = novel.title,
            author = novel.author ?: existing?.author,
            description = novel.description ?: existing?.description,
            coverUrl = novel.coverUrl ?: existing?.coverUrl,
            genres = novel.genres.joinToString(","),
            status = novel.status.name,
        )
        return if (existing != null) {
            novelDao.update(entity); entity.id
        } else {
            novelDao.upsert(entity)
        }
    }

    suspend fun addToLibrary(novelId: Long, inLibrary: Boolean) {
        novelDao.setInLibrary(novelId, inLibrary, System.currentTimeMillis())
    }

    /** Fetches chapters from the source and persists any new ones. */
    suspend fun refreshChapters(novelId: Long) {
        val novel = novelDao.getById(novelId) ?: return
        val source = sourceManager.get(novel.sourceId) ?: return
        val remote = source.getChapterList(novel.url)
        val entities = remote.mapIndexed { index, ch: SChapter ->
            ChapterEntity(
                novelId = novelId,
                url = ch.url,
                name = ch.name,
                number = ch.number,
                dateUpload = ch.dateUpload,
                sourceOrder = index,
            )
        }
        chapterDao.insertAll(entities)
    }

    suspend fun markRead(chapterId: Long, read: Boolean, offset: Float = 0f) {
        chapterDao.setReadState(chapterId, read, offset)
    }

    suspend fun saveProgress(novelId: Long, chapterId: Long, offset: Float) {
        chapterDao.setReadState(chapterId, read = offset >= 0.98f, offset = offset)
        novelDao.setLastReadChapter(novelId, chapterId)
        historyDao.upsert(
            HistoryEntity(
                novelId = novelId,
                chapterId = chapterId,
                readAt = System.currentTimeMillis(),
            ),
        )
    }
}
