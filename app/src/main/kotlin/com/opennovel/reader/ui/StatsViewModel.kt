package com.opennovel.reader.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.opennovel.reader.NovelReaderApp
import com.opennovel.reader.data.LibraryRepository
import com.opennovel.reader.data.db.ContentType
import com.opennovel.reader.source.SourceManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.TimeUnit

/** One source and how many library entries come from it. */
data class SourceStat(val sourceName: String, val count: Int)

/**
 * Everything the Statistics screen shows, computed in one pass.
 *
 * A single snapshot rather than a flow per number: the figures are cross-cutting
 * (chapter tallies are only meaningful once narrowed to library entries), and
 * emitting them independently would let the screen render mutually inconsistent
 * totals while the flows caught up with each other.
 */
data class LibraryStats(
    val totalEntries: Int = 0,
    val comics: Int = 0,
    val novels: Int = 0,
    val untypedEntries: Int = 0,
    val categories: Int = 0,
    val withUnread: Int = 0,
    val started: Int = 0,
    val finished: Int = 0,
    val totalChapters: Int = 0,
    val readChapters: Int = 0,
    val unreadChapters: Int = 0,
    val downloadedChapters: Int = 0,
    /**
     * Lower bound only. No query counts bookmarks across the whole chapter
     * table, so this is the distinct bookmarked chapters visible through the
     * Updates feed (capped at 500 rows) and the download list. See the
     * integration note on [StatsViewModel].
     */
    val bookmarkedChaptersSeen: Int = 0,
    val entriesRead: Int = 0,
    val lastReadTitle: String? = null,
    val lastReadAt: Long = 0L,
    val entriesReadLastWeek: Int = 0,
    val distinctSources: Int = 0,
    val topSources: List<SourceStat> = emptyList(),
)

/**
 * Backs the Statistics screen.
 *
 * Every figure is derived from the flows the library already exposes rather than
 * from dedicated aggregate queries, so no new SQL is needed. The one figure that
 * cannot be derived exactly is the bookmark count — see
 * [LibraryStats.bookmarkedChaptersSeen].
 */
class StatsViewModel(
    private val repo: LibraryRepository,
    private val sourceManager: SourceManager,
) : ViewModel() {

    /**
     * Chapter tallies arrive for *every* novel ever cached, including ones the
     * user only browsed, so they are narrowed to library ids before summing —
     * otherwise a few searches would inflate the library's chapter totals.
     */
    private val libraryFacts = combine(
        repo.observeLibrary(),
        repo.observeNovelCounts(),
        repo.observeCategories(),
        repo.observeHistory(),
        repo.observeDownloaded(),
    ) { library, counts, categories, history, downloaded ->
        val libraryIds = library.mapTo(mutableSetOf()) { it.id }
        val scopedCounts = counts.filter { it.novelId in libraryIds }
        val weekAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)

        val sourceCounts = library.groupingBy { it.sourceId }.eachCount()

        LibraryStats(
            totalEntries = library.size,
            comics = library.count { ContentType.from(it.contentType) == ContentType.COMIC },
            novels = library.count { ContentType.from(it.contentType) == ContentType.NOVEL },
            untypedEntries = library.count { ContentType.from(it.contentType) == ContentType.UNKNOWN },
            categories = categories.size,
            withUnread = scopedCounts.count { it.unread > 0 },
            started = scopedCounts.count { it.started },
            // "Finished" means every known chapter is read. Source-reported
            // completion status is unreliable and says nothing about progress.
            finished = scopedCounts.count { it.total > 0 && it.unread == 0 },
            totalChapters = scopedCounts.sumOf { it.total },
            readChapters = scopedCounts.sumOf { it.total - it.unread },
            unreadChapters = scopedCounts.sumOf { it.unread },
            downloadedChapters = scopedCounts.sumOf { it.downloaded },
            bookmarkedChaptersSeen = downloaded.count { it.bookmark },
            entriesRead = history.size,
            lastReadTitle = history.firstOrNull()?.title,
            lastReadAt = history.firstOrNull()?.readAt ?: 0L,
            entriesReadLastWeek = history.count { it.readAt >= weekAgo },
            distinctSources = sourceCounts.size,
            topSources = sourceCounts.entries
                .sortedByDescending { it.value }
                .take(5)
                .map { (sourceId, count) ->
                    SourceStat(
                        // Entries outlive their extension, so name the source
                        // unknown rather than dropping it from the breakdown.
                        sourceName = sourceManager.get(sourceId)?.name ?: "Unknown source ($sourceId)",
                        count = count,
                    )
                },
        ) to downloaded.mapTo(mutableSetOf()) { it.chapterId }
    }

    val stats: StateFlow<LibraryStats> =
        combine(libraryFacts, repo.observeRecentChapters()) { (base, downloadedIds), recent ->
            // Union by chapter id: a chapter can appear in both feeds, and
            // counting it twice would overstate an already-partial figure.
            base.copy(
                bookmarkedChaptersSeen = base.bookmarkedChaptersSeen +
                    recent.count { it.bookmark && it.chapterId !in downloadedIds },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryStats())

    companion object {
        /**
         * Built here rather than through [VmFactory] so the screen works without
         * a change to a file this feature does not own; swap to the shared
         * factory once `StatsViewModel` is registered there.
         */
        fun factory(context: Context): ViewModelProvider.Factory {
            val container = (context.applicationContext as NovelReaderApp).container
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    StatsViewModel(container.libraryRepository, container.sourceManager) as T
            }
        }
    }
}
