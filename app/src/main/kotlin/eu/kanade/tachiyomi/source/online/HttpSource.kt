package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.security.MessageDigest

/**
 * Faithful host runtime of the Tachiyomi extensions-lib `HttpSource`. Extensions
 * subclass this (usually via `ParsedHttpSource`), overriding the request builders
 * and parse hooks; the suspend catalogue methods here drive them off the network.
 */
abstract class HttpSource : CatalogueSource {

    protected val network: NetworkHelper by injectLazy()

    abstract val baseUrl: String
    abstract override val lang: String
    override val supportsLatest: Boolean = true

    open val client: OkHttpClient get() = network.client

    protected open val versionId: Int = 1

    override val id: Long by lazy { generateId(name, lang, versionId) }

    open val headers: Headers by lazy { headersBuilder().build() }

    protected open fun headersBuilder(): Headers.Builder =
        Headers.Builder().add("User-Agent", DEFAULT_USER_AGENT)

    // --- request builders (extensions override) ---

    protected abstract fun popularMangaRequest(page: Int): Request
    protected abstract fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request
    protected open fun latestUpdatesRequest(page: Int): Request =
        throw UnsupportedOperationException("Not used")

    protected open fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)
    protected open fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)
    protected open fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    // --- parse hooks (extensions override) ---

    protected abstract fun popularMangaParse(response: Response): MangasPage
    protected abstract fun searchMangaParse(response: Response): MangasPage
    protected open fun latestUpdatesParse(response: Response): MangasPage =
        throw UnsupportedOperationException("Not used")

    protected abstract fun mangaDetailsParse(response: Response): SManga
    protected abstract fun chapterListParse(response: Response): List<SChapter>
    protected abstract fun pageListParse(response: Response): List<Page>
    protected open fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not used")

    // --- coroutine catalogue API driving the hooks ---

    override suspend fun getPopularManga(page: Int): MangasPage = withContext(Dispatchers.IO) {
        popularMangaParse(client.newCall(popularMangaRequest(page)).await())
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        withContext(Dispatchers.IO) {
            searchMangaParse(client.newCall(searchMangaRequest(page, query, filters)).await())
        }

    override suspend fun getLatestUpdates(page: Int): MangasPage = withContext(Dispatchers.IO) {
        latestUpdatesParse(client.newCall(latestUpdatesRequest(page)).await())
    }

    override suspend fun getMangaDetails(manga: SManga): SManga = withContext(Dispatchers.IO) {
        mangaDetailsParse(client.newCall(mangaDetailsRequest(manga)).await()).apply { initialized = true }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = withContext(Dispatchers.IO) {
        chapterListParse(client.newCall(chapterListRequest(manga)).await())
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        pageListParse(client.newCall(pageListRequest(chapter)).await())
    }

    suspend fun getImageUrl(page: Page): String = withContext(Dispatchers.IO) {
        imageUrlParse(client.newCall(imageUrlRequest(page)).await())
    }

    protected open fun imageUrlRequest(page: Page): Request = GET(page.url, headers)

    open fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

    open fun getMangaUrl(manga: SManga): String = baseUrl + manga.url
    open fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private fun generateId(name: String, lang: String, versionId: Int): Long {
            val key = "${name.lowercase()}/$lang/$versionId"
            val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
            var id = 0L
            for (i in 0 until 8) id = id shl 8 or (bytes[i].toLong() and 0xff)
            return id and Long.MAX_VALUE
        }
    }
}
