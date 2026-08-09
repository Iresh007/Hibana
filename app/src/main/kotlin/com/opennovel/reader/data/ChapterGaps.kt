package com.opennovel.reader.data

import com.opennovel.reader.data.db.ChapterEntity
import kotlin.math.floor

/**
 * A break in chapter numbering — chapters the source never listed.
 *
 * Sources drop chapters for real reasons (licensing takedowns, scanlation gaps,
 * bad parsing), and a reader hitting an unexplained jump usually assumes the app
 * lost them. Surfacing the gap explicitly turns that into information.
 */
data class ChapterGap(
    /** Last chapter number present before the gap. */
    val after: Float,
    /** First chapter number present after the gap. */
    val before: Float,
) {
    /** How many whole chapters appear to be missing. */
    val count: Int get() = (floor(before) - floor(after)).toInt() - 1

    val label: String
        get() = if (count == 1) {
            "1 chapter missing"
        } else {
            "$count chapters missing"
        }
}

/**
 * Finds numbering gaps in an ordered chapter list.
 *
 * Only whole-number jumps count: sources routinely publish 12.5 style side
 * chapters, and treating those as gaps would flag almost every series. Chapters
 * without a usable number (-1) are skipped rather than guessed at, since
 * inventing an order would produce false positives.
 */
fun findChapterGaps(chapters: List<ChapterEntity>): Map<Long, ChapterGap> {
    val numbered = chapters
        .filter { it.number >= 0f }
        .sortedBy { it.number }
    if (numbered.size < 2) return emptyMap()

    val gaps = mutableMapOf<Long, ChapterGap>()
    for (i in 1 until numbered.size) {
        val previous = numbered[i - 1]
        val current = numbered[i]
        val expectedNext = floor(previous.number) + 1
        if (floor(current.number) > expectedNext) {
            // Attach the gap to the chapter that follows it, which is where the
            // reader notices something is missing.
            gaps[current.id] = ChapterGap(after = previous.number, before = current.number)
        }
    }
    return gaps
}
