package com.opennovel.reader.data

import com.opennovel.reader.data.db.CategoryDao
import com.opennovel.reader.data.db.CategoryEntity
import com.opennovel.reader.data.db.ContentType
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
    /**
     * Whether history writes are currently suppressed.
     *
     * Passed as a function rather than a `SettingsRepository` so this repository
     * keeps depending on nothing but its DAOs and the source manager, and so the
     * value is read at the moment of the write — a snapshot captured at
     * construction would keep recording after the user switched incognito on.
     */
    private val incognito: suspend () -> Boolean = { false },
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

    /** Unread/downloaded tallies per novel, for library cover badges. */
    fun observeNovelCounts(): Flow<List<com.opennovel.reader.data.db.NovelCounts>> =
        chapterDao.observeNovelCounts()

    /** Newest chapters across the library — the Updates tab. */
    fun observeRecentChapters(): Flow<List<com.opennovel.reader.data.db.ChapterWithNovel>> =
        chapterDao.observeRecentChapters()

    /** Everything currently downloaded, for the download manager. */
    fun observeDownloaded(): Flow<List<com.opennovel.reader.data.db.ChapterWithNovel>> =
        chapterDao.observeDownloaded()

    /** Release timestamps per library entry, for the upcoming-releases estimate. */
    fun observeReleaseTimes(): Flow<List<com.opennovel.reader.data.db.ChapterRelease>> =
        chapterDao.observeReleaseTimes()

    /** Exact library-wide chapter tallies, for Statistics. */
    fun observeBookmarkedCount(): Flow<Int> = chapterDao.observeBookmarkedCount()

    fun observeDownloadedCount(): Flow<Int> = chapterDao.observeDownloadedCount()

    suspend fun undownloadedChapterIds(novelId: Long): List<Long> =
        chapterDao.undownloadedIds(novelId)

    /**
     * Refreshes chapters for every library novel. Failures are per-novel so one
     * dead source can't abort the whole sweep.
     *
     * Returns a [RefreshReport] rather than a bare success count. A sweep that
     * finds nothing and a sweep where every source was missing or threw look
     * identical from the outside, and reporting only "ok" made a broken refresh
     * indistinguishable from an up-to-date library — the user could not tell
     * whether the feature was working.
     */
    suspend fun refreshLibrary(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): RefreshReport {
        val novels = novelDao.getAllInLibrary()
        var newChapters = 0
        var failed = 0
        var skipped = 0
        novels.forEachIndexed { index, novel ->
            when (val outcome = runCatching { refreshChapters(novel.id) }.getOrNull()) {
                null -> failed++
                RefreshOutcome.NO_SOURCE -> skipped++
                else -> newChapters += outcome.newChapters
            }
            onProgress(index + 1, novels.size)
        }
        return RefreshReport(
            scanned = novels.size,
            newChapters = newChapters,
            failed = failed,
            skippedNoSource = skipped,
        )
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
            // Only ever fills in an unset type, so a user's manual correction is
            // never overwritten by a later refresh.
            contentType = existing?.contentType
                ?.takeIf { ContentType.from(it) != ContentType.UNKNOWN }
                ?: defaultContentTypeFor(sourceId).name,
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

    /**
     * Whether a source's entries are comics or prose, inferred from what the
     * source can actually serve: only comic sources can produce page images.
     *
     * Inferred from capability rather than from which ecosystem the extension
     * came from, because the ecosystems are not clean proxies — Mihon sources
     * carry text works and IReader hosts illustrated ones — and because the
     * capability is what the decision is really about: which reader to open.
     * It is only a default; [setContentType] lets the user correct any entry,
     * and that correction sticks.
     */
    private fun defaultContentTypeFor(sourceId: Long): ContentType =
        when (sourceManager.get(sourceId)) {
            is com.opennovel.reader.source.ImageChapterSource -> ContentType.COMIC
            null -> ContentType.UNKNOWN
            else -> ContentType.NOVEL
        }

    suspend fun setContentType(novelId: Long, type: ContentType) =
        novelDao.setContentType(novelId, type.name)

    /**
     * Fetches chapters from the source and persists any new ones, stamping each
     * with the time we first saw it so the Updates feed can order by it.
     *
     * A missing source is reported as [RefreshOutcome.NO_SOURCE] rather than
     * treated as success. Returning silently here was why a refresh over a
     * library whose extensions had not loaded (or were untrusted) looked like it
     * ran cleanly while doing nothing at all.
     */
    suspend fun refreshChapters(novelId: Long): RefreshOutcome {
        val novel = novelDao.getById(novelId) ?: return RefreshOutcome.NO_SOURCE
        val source = sourceManager.get(novel.sourceId) ?: return RefreshOutcome.NO_SOURCE
        val remote = source.getChapterList(novel.url)
        val now = System.currentTimeMillis()
        val entities = remote.mapIndexed { index, ch: SChapter ->
            ChapterEntity(
                novelId = novelId,
                url = ch.url,
                name = ch.name,
                number = ch.number,
                dateUpload = ch.dateUpload,
                dateFetch = now,
                sourceOrder = index,
            )
        }
        // IGNORE returns -1 for rows we already had, so the count of non-negative
        // ids is exactly the number of genuinely new chapters.
        val ids = chapterDao.insertAllReturningIds(entities)
        return RefreshOutcome(newChapters = ids.count { it >= 0 })
    }

    /**
     * Marks a chapter read/unread. Marking read also records history: reading is
     * reading whether it happened in the reader or by tapping through a chapter
     * list, and History that only reflected one of those looked broken.
     */
    suspend fun markRead(chapterId: Long, read: Boolean, offset: Float = 0f) {
        chapterDao.setReadState(chapterId, read, offset)
        if (read) {
            chapterDao.getById(chapterId)?.let { recordHistory(it.novelId, chapterId) }
        }
    }

    suspend fun setBookmark(chapterId: Long, bookmark: Boolean) =
        chapterDao.setBookmark(chapterId, bookmark)

    /** Batch equivalents, for multi-select in the chapter list. */
    suspend fun markReadFor(ids: List<Long>, read: Boolean) {
        if (ids.isEmpty()) return
        chapterDao.setReadStateFor(ids, read, if (read) 1f else 0f)
        if (read) {
            chapterDao.getByIds(ids).maxByOrNull { it.sourceOrder }
                ?.let { recordHistory(it.novelId, it.id) }
        }
    }

    suspend fun setBookmarkFor(ids: List<Long>, bookmark: Boolean) {
        if (ids.isNotEmpty()) chapterDao.setBookmarkFor(ids, bookmark)
    }

    /**
     * Records that a chapter was opened. Called when the reader loads a chapter,
     * not only when scroll progress changes — a chapter short enough to need no
     * scrolling, or one the user backed out of, previously left no trace in
     * History at all.
     *
     * Honours incognito mode. The resume pointer is still updated so closing and
     * reopening an entry returns to the right place; only the visible History
     * trail is suppressed, which is what incognito is actually promising.
     */
    suspend fun recordHistory(novelId: Long, chapterId: Long) {
        novelDao.setLastReadChapter(novelId, chapterId)
        if (incognito()) return
        historyDao.upsert(
            HistoryEntity(
                novelId = novelId,
                chapterId = chapterId,
                readAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun saveProgress(novelId: Long, chapterId: Long, offset: Float) {
        chapterDao.setReadState(chapterId, read = offset >= 0.98f, offset = offset)
        recordHistory(novelId, chapterId)
    }
}

/** Result of refreshing one entry. */
@JvmInline
value class RefreshOutcome(val newChapters: Int) {
    companion object {
        /** The owning source isn't loaded — extension missing, disabled, or untrusted. */
        val NO_SOURCE = RefreshOutcome(-1)
    }
}

/**
 * Outcome of a full library sweep, detailed enough that the UI can say what
 * actually happened rather than just stopping the spinner.
 */
data class RefreshReport(
    val scanned: Int,
    val newChapters: Int,
    val failed: Int,
    val skippedNoSource: Int,
) {
    val hadProblems: Boolean get() = failed > 0 || skippedNoSource > 0

    /** One line suitable for a snackbar. */
    fun summary(): String = when {
        scanned == 0 -> "Library is empty — add something first"
        newChapters > 0 && hadProblems ->
            "$newChapters new chapter${s(newChapters)}, ${failed + skippedNoSource} entr${if (failed + skippedNoSource == 1) "y" else "ies"} could not be checked"
        newChapters > 0 -> "$newChapters new chapter${s(newChapters)}"
        skippedNoSource == scanned && scanned > 0 ->
            "No sources available — install or trust the extensions first"
        hadProblems -> "No new chapters · ${failed + skippedNoSource} could not be checked"
        else -> "No new chapters"
    }

    private fun s(n: Int) = if (n == 1) "" else "s"
}
