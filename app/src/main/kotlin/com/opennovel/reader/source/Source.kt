package com.opennovel.reader.source

import com.opennovel.reader.source.model.ChapterText
import com.opennovel.reader.source.model.NovelsPage
import com.opennovel.reader.source.model.SChapter
import com.opennovel.reader.source.model.SNovel

/**
 * The contract every content source (built-in or installed extension) must
 * implement. Deliberately small and Mihon-inspired so third-party extensions
 * can be authored against a stable surface.
 *
 * All methods are suspend and expected to run off the main thread. Sources must
 * not touch Android UI; they only fetch and parse.
 */
interface Source {
    /** Stable, unique id. Convention: a 64-bit hash of "name/lang/version". */
    val id: Long

    /** Human-readable name shown in the source picker. */
    val name: String

    /** ISO-639-1 language code, e.g. "en". */
    val lang: String

    /** Base URL for HTTP sources; empty for local. */
    val baseUrl: String

    /** Popular/browse listing, paginated. */
    suspend fun getPopularNovels(page: Int): NovelsPage

    /** Free-text search. */
    suspend fun searchNovels(query: String, page: Int): NovelsPage

    /** Full details for a novel given its source-relative url. */
    suspend fun getNovelDetails(url: String): SNovel

    /** Ordered chapter list (index 0 = first chapter). */
    suspend fun getChapterList(novelUrl: String): List<SChapter>

    /** Chapter body as clean paragraphs. */
    suspend fun getChapterText(chapterUrl: String): ChapterText
}
