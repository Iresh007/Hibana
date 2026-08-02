package eu.kanade.tachiyomi.source

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga

/**
 * Base source contract from the Tachiyomi extensions-lib (coroutine API). Default
 * impls throw so extensions only override what they use.
 */
interface Source {
    val id: Long
    val name: String
    val lang: String get() = ""

    suspend fun getMangaDetails(manga: SManga): SManga = throw UnsupportedOperationException("Not implemented")
    suspend fun getChapterList(manga: SManga): List<SChapter> = throw UnsupportedOperationException("Not implemented")
    suspend fun getPageList(chapter: SChapter): List<Page> = throw UnsupportedOperationException("Not implemented")
}

/** A source with a browsable catalogue (popular / latest / search). */
interface CatalogueSource : Source {
    override val lang: String
    val supportsLatest: Boolean

    suspend fun getPopularManga(page: Int): MangasPage = throw UnsupportedOperationException("Not implemented")
    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        throw UnsupportedOperationException("Not implemented")
    suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException("Not implemented")

    fun getFilterList(): FilterList = FilterList()
}

/** A source that exposes user-configurable preferences. */
interface ConfigurableSource : Source {
    fun setupPreferenceScreen(screen: PreferenceScreen)
}

/** Marker: source is not rate-metered (affects Mihon scheduling only). */
interface UnmeteredSource : Source

/** Yields multiple sources from one extension class. */
interface SourceFactory {
    fun createSources(): List<Source>
}
