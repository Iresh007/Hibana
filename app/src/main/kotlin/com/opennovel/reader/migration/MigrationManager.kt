package com.opennovel.reader.migration

import com.opennovel.reader.data.LibraryRepository
import com.opennovel.reader.data.db.ChapterDao
import com.opennovel.reader.data.db.NovelDao
import com.opennovel.reader.data.db.NovelEntity
import com.opennovel.reader.source.Source
import com.opennovel.reader.source.SourceManager
import com.opennovel.reader.source.model.SNovel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.floor

/** A possible home for a library entry on another source. */
data class MigrationCandidate(
    val sourceId: Long,
    val sourceName: String,
    val novel: SNovel,
    /** 0..1 title/author confidence from [TitleMatcher]. */
    val score: Double,
    /** Chapters the candidate offers; -1 until counted. */
    val chapterCount: Int = -1,
)

/**
 * What a migration carries across, and whether the original survives it.
 *
 * Defaults reproduce the previous behaviour (carry everything, retire the old
 * entry) so callers that don't care keep working unchanged.
 */
data class MigrationOptions(
    val chaptersRead: Boolean = true,
    val categories: Boolean = true,
    val bookmarks: Boolean = true,
    /** Move takes the original out of the library; copy leaves both shelved. */
    val removeOriginal: Boolean = true,
)

/** Everything needed to preview one entry's migration options. */
data class MigrationSearch(
    val novel: NovelEntity,
    val currentChapterCount: Int,
    val candidates: List<MigrationCandidate>,
)

/**
 * Finds the same work on other sources and moves a library entry across,
 * carrying reading progress.
 *
 * The hard part is not searching — it is *not losing progress*. Chapter URLs are
 * source-specific, so read state is re-matched by chapter number, which is the
 * only value that means the same thing on both sides.
 */
class MigrationManager(
    private val repo: LibraryRepository,
    private val novelDao: NovelDao,
    private val chapterDao: ChapterDao,
    private val sourceManager: SourceManager,
) {

    /**
     * Searches every other source for [novel], concurrently.
     *
     * The novel's current source is excluded (migrating onto itself is
     * meaningless), and sources are queried in parallel because a sequential
     * sweep across a dozen extensions is unusably slow. A failing source yields
     * no candidates rather than failing the search.
     */
    suspend fun findCandidates(
        novel: NovelEntity,
        includeChapterCounts: Boolean = true,
        /**
         * Sources to search. Null means every source. Narrowing matters: a large
         * extension list makes an all-sources sweep slow and buries the two or
         * three sources you actually read from.
         */
        targetSourceIds: Set<Long>? = null,
    ): MigrationSearch = coroutineScope {
        val current = chapterDao.getForNovel(novel.id)
        val others = sourceManager.catalogueSources()
            .filter { it.id != novel.sourceId }
            .filter { targetSourceIds == null || it.id in targetSourceIds }

        val results = others.map { source ->
            async(Dispatchers.IO) { bestCandidate(source, novel, includeChapterCounts) }
        }.mapNotNull { it.await() }

        MigrationSearch(
            novel = novel,
            currentChapterCount = current.size,
            candidates = results.sortedWith(
                compareByDescending<MigrationCandidate> { it.score }
                    .thenByDescending { it.chapterCount },
            ),
        )
    }

    private suspend fun bestCandidate(
        source: Source,
        novel: NovelEntity,
        includeChapterCounts: Boolean,
    ): MigrationCandidate? {
        val page = runCatching { source.searchNovels(novel.title, 1) }.getOrNull() ?: return null

        val best = page.novels
            .map { it to TitleMatcher.score(novel.title, novel.author, it.title, it.author) }
            .filter { it.second >= TitleMatcher.MIN_SCORE }
            .maxByOrNull { it.second } ?: return null

        val count = if (includeChapterCounts) {
            runCatching { source.getChapterList(best.first.url).size }.getOrDefault(-1)
        } else {
            -1
        }

        return MigrationCandidate(
            sourceId = source.id,
            sourceName = source.name,
            novel = best.first,
            score = best.second,
            chapterCount = count,
        )
    }

    /**
     * Moves [novel] onto [candidate], transferring progress, then retires the old
     * entry.
     *
     * Read state is re-matched by **chapter number**, not URL or list position:
     * URLs never survive a source change, and positions break the moment the two
     * sources disagree on chapter count — which is usually the reason for
     * migrating. Chapters without a usable number fall back to a normalised name
     * match. Anything unmatched is simply left unread rather than guessed at.
     *
     * On a move the old entry is removed from the library but kept in the
     * database, so a mistaken migration can still be recovered from history; on a
     * copy it stays shelved alongside the new one.
     */
    suspend fun migrate(
        novel: NovelEntity,
        candidate: MigrationCandidate,
        options: MigrationOptions = MigrationOptions(),
    ): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val newId = repo.cacheNovel(candidate.sourceId, candidate.novel)
                repo.addToLibrary(newId, true)
                repo.refreshChapters(newId)

                val oldChapters = chapterDao.getForNovel(novel.id)
                val newChapters = chapterDao.getForNovel(newId)

                val newByNumber = newChapters
                    .filter { it.number >= 0f }
                    .associateBy { floor(it.number.toDouble()).toInt() }
                val newByName = newChapters.associateBy { TitleMatcher.normalize(it.name) }

                oldChapters.forEach { old ->
                    val match = when {
                        old.number >= 0f -> newByNumber[floor(old.number.toDouble()).toInt()]
                        else -> newByName[TitleMatcher.normalize(old.name)]
                    } ?: return@forEach
                    if (options.chaptersRead && (old.read || old.lastReadOffset > 0f)) {
                        chapterDao.setReadState(match.id, old.read, old.lastReadOffset)
                    }
                    if (options.bookmarks && old.bookmark) {
                        chapterDao.setBookmark(match.id, true)
                    }
                }

                if (options.chaptersRead) {
                    // Re-point "continue reading" at the equivalent chapter.
                    val oldResume = novel.lastReadChapterId?.let { id -> oldChapters.firstOrNull { it.id == id } }
                    val newResume = oldResume?.let { old ->
                        if (old.number >= 0f) {
                            newByNumber[floor(old.number.toDouble()).toInt()]
                        } else {
                            newByName[TitleMatcher.normalize(old.name)]
                        }
                    }
                    newResume?.let { novelDao.setLastReadChapter(newId, it.id) }
                }

                if (options.categories) {
                    // Keep shelf placement so the entry doesn't vanish from its category.
                    val categories = repo.categoryIdsForNovel(novel.id).toSet()
                    if (categories.isNotEmpty()) repo.setNovelCategories(newId, categories)
                }

                if (options.removeOriginal) {
                    repo.addToLibrary(novel.id, false)
                    repo.removeHistory(novel.id)
                }

                newId
            }
        }
}
