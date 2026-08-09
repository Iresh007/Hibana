package com.opennovel.reader.migration

import java.util.Locale

/**
 * Decides whether an entry on another source is the *same work* as one in the
 * library.
 *
 * There is no shared identifier across sources — only titles, which differ in
 * punctuation, romanisation, and decoration ("Solo Leveling (Official)",
 * "Solo Leveling [Novel]", "solo-leveling"). So matching is a normalise-then-
 * score problem, and the score is surfaced to the user rather than trusted
 * blindly: a wrong auto-migration silently destroys reading progress, so the
 * user always confirms.
 */
object TitleMatcher {

    /** Bracketed qualifiers and format tags sources bolt onto titles. */
    private val decorations = Regex("""[\(\[\{].*?[\)\]\}]""")
    private val formatWords = setOf(
        "novel", "webnovel", "ln", "wn", "manga", "manhwa", "manhua", "comic",
        "official", "raw", "translated", "english", "eng", "complete", "completed",
        "season", "part", "vol", "volume",
    )
    private val nonAlphanumeric = Regex("""[^\p{L}\p{N}\s]""")
    private val whitespace = Regex("""\s+""")

    /**
     * Reduces a title to a comparable form: decorations dropped, punctuation
     * stripped, format words removed, whitespace collapsed, lowercased.
     */
    fun normalize(title: String): String = title
        .lowercase(Locale.ROOT)
        .replace(decorations, " ")
        .replace(nonAlphanumeric, " ")
        .split(whitespace)
        .filter { it.isNotBlank() && it !in formatWords }
        .joinToString(" ")

    /**
     * Similarity in 0..1 between two titles.
     *
     * Combines token overlap (order-insensitive, handles reordered subtitles)
     * with normalised edit distance (catches typos and romanisation drift).
     * Neither alone is sufficient: token overlap calls "Hero Returns" and
     * "Returns Hero" identical, while edit distance punishes a missing subtitle
     * far too harshly.
     */
    fun similarity(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0

        val tokensA = na.split(" ").toSet()
        val tokensB = nb.split(" ").toSet()
        val overlap = tokensA.intersect(tokensB).size.toDouble() /
            tokensA.union(tokensB).size.toDouble()

        val distance = levenshtein(na, nb).toDouble()
        val edit = 1.0 - (distance / maxOf(na.length, nb.length).toDouble())

        // Token overlap is the more reliable signal for real-world title drift.
        return (overlap * 0.65) + (edit.coerceAtLeast(0.0) * 0.35)
    }

    /** Author agreement is a strong confirmation, so it nudges the score up. */
    fun score(
        sourceTitle: String,
        sourceAuthor: String?,
        candidateTitle: String,
        candidateAuthor: String?,
    ): Double {
        var s = similarity(sourceTitle, candidateTitle)
        if (!sourceAuthor.isNullOrBlank() && !candidateAuthor.isNullOrBlank()) {
            if (similarity(sourceAuthor, candidateAuthor) > 0.8) {
                s = (s + 0.15).coerceAtMost(1.0)
            }
        }
        return s
    }

    /** Below this, a candidate is too weak to offer without misleading the user. */
    const val MIN_SCORE = 0.55

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost,
                )
            }
            val swap = previous; previous = current; current = swap
        }
        return previous[b.length]
    }
}
