package com.opennovel.reader.source.model

/**
 * Lightweight, source-facing domain models. Extensions/sources return these;
 * the app maps them into Room entities when a novel is added to the library.
 * Mirrors the shape of Mihon/Tachiyomi's SManga/SChapter/Page for familiarity.
 */

/** A novel as returned by a source's catalogue or search. */
data class SNovel(
    /** Source-relative identifier (usually the detail-page URL path). */
    val url: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val coverUrl: String? = null,
    val genres: List<String> = emptyList(),
    val status: NovelStatus = NovelStatus.UNKNOWN,
)

/** A chapter reference within a novel. */
data class SChapter(
    val url: String,
    val name: String,
    /** Optional chapter number for sorting; -1 when unknown. */
    val number: Float = -1f,
    /** Epoch millis of upload/release date, 0 when unknown. */
    val dateUpload: Long = 0L,
)

enum class NovelStatus { ONGOING, COMPLETED, HIATUS, CANCELLED, UNKNOWN }

/** A page/segment of paginated results. */
data class NovelsPage(
    val novels: List<SNovel>,
    val hasNextPage: Boolean,
)

/** Chapter body returned as plain text (paragraphs). Sources strip HTML. */
data class ChapterText(
    val paragraphs: List<String>,
) {
    val plain: String get() = paragraphs.joinToString("\n\n")
}
