package com.opennovel.reader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opennovel.reader.data.LibraryRepository
import com.opennovel.reader.data.db.NovelEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/** A library entry with an estimated date for its next chapter. */
data class UpcomingRelease(
    val novelId: Long,
    val title: String,
    val coverUrl: String?,
    /** Estimated epoch millis of the next release. */
    val expectedAt: Long,
    /** Typical gap between chapters, in days, behind the estimate. */
    val intervalDays: Int,
    /**
     * How much to trust the estimate. Low when a series releases erratically, so
     * the UI can say so rather than presenting a guess as a schedule.
     */
    val confident: Boolean,
)

/**
 * Estimates when each library entry will next publish, from the cadence it has
 * been publishing at.
 *
 * Sources do not expose schedules, so this is inferred rather than known. It
 * uses the *median* gap between recent chapters, not the mean: hiatuses and
 * bulk back-catalogue imports produce outliers big enough to drag a mean into
 * uselessness, while the median ignores them.
 */
class UpcomingViewModel(private val repo: LibraryRepository) : ViewModel() {

    val upcoming: StateFlow<List<UpcomingRelease>> =
        combine(repo.observeLibrary(), repo.observeReleaseTimes()) { novels, releases ->
            val byNovel = releases.groupBy { it.novelId }
            val titles = novels.associateBy { it.id }
            byNovel.mapNotNull { (novelId, rows) ->
                estimate(titles[novelId] ?: return@mapNotNull null, rows.map { it.releasedAt })
            }.sortedBy { it.expectedAt }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Entries grouped by the day they are expected, for a calendar-style list. */
    val byDay: StateFlow<List<Pair<Long, List<UpcomingRelease>>>> =
        upcoming.map { list ->
            list.groupBy { startOfDay(it.expectedAt) }.toList().sortedBy { it.first }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun estimate(novel: NovelEntity, timesDesc: List<Long>): UpcomingRelease? {
        // Two chapters give one gap, which is a coincidence rather than a
        // cadence. Four gives three gaps, enough for a median to mean something.
        val times = timesDesc.sortedDescending().take(12)
        if (times.size < 4) return null

        val gaps = times.zipWithNext { newer, older -> newer - older }.filter { it > 0 }
        if (gaps.size < 3) return null

        val sorted = gaps.sorted()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        }
        if (median <= 0) return null

        val latest = times.first()
        val now = System.currentTimeMillis()

        // Roll forward past due dates rather than showing a date in the past: a
        // series that has slipped is still expected, just later.
        var expected = latest + median
        if (expected < now) {
            val missed = ((now - latest).toDouble() / median).roundToLong().coerceAtLeast(1)
            expected = latest + median * (missed + 1)
        }

        // Spread within half a median of each other means a regular schedule.
        val spread = sorted.last() - sorted.first()
        return UpcomingRelease(
            novelId = novel.id,
            title = novel.title,
            coverUrl = novel.coverUrl,
            expectedAt = expected,
            intervalDays = TimeUnit.MILLISECONDS.toDays(median).toInt().coerceAtLeast(1),
            confident = spread <= median / 2,
        )
    }

    private fun startOfDay(millis: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = millis
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}
