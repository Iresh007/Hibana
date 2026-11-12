package com.opennovel.reader.extension

import android.util.Log
import com.opennovel.reader.source.ImageChapterSource
import com.opennovel.reader.source.ImagePage
import com.opennovel.reader.source.Source
import com.opennovel.reader.source.model.ChapterText
import com.opennovel.reader.source.model.NovelStatus
import com.opennovel.reader.source.model.NovelsPage
import com.opennovel.reader.source.model.SChapter
import com.opennovel.reader.source.model.SNovel
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import eu.kanade.tachiyomi.source.Source as TachiyomiSource
import eu.kanade.tachiyomi.source.model.SChapter as NativeChapter

/**
 * Bridges a loaded Tachiyomi/Mihon (or Manatan) source onto Hibana's [Source] contract using the
 * real source-api (extension-lib 1.4 and 1.6 shapes). Mihon/Manatan sources are *manga* sources —
 * chapters resolve to image pages, not novel text — so [getChapterText] returns an empty body and
 * readers use [ImageChapterSource].
 *
 * Network entry points are wrapped in [Dispatchers.IO]: the default implementations of the
 * deprecated Rx API (`fetchPopularManga`, ...) execute the OkHttp call on the collecting thread.
 *
 * @param pkgId package of the extension that produced this source, so the UI can map a source back
 *   to its extension without matching on names.
 */
class MihonSourceAdapter(
    private val native: TachiyomiSource,
    private val ecosystem: Ecosystem,
    val pkgId: String? = null,
) : Source, ImageChapterSource {

    private val http: HttpSource? = native as? HttpSource

    /**
     * Non-null when the extension declares its own settings screen. The adapter
     * is the only object that still holds the extension instance once loading is
     * done, so the preferences UI has to reach it through here.
     */
    val configurable: ConfigurableSource? = native as? ConfigurableSource

    override val id: Long = native.id
    override val name: String = native.name
    override val lang: String = native.lang.ifBlank { "all" }
    override val baseUrl: String = runCatching { http?.baseUrl }.getOrNull().orEmpty()

    override val supportsLatest: Boolean = runCatching { native.supportsLatest }.getOrDefault(false)

    // Sources round-trip data (notably 1.6 `memo`) through the objects they returned, and Hibana's
    // models only carry urls, so the originals are remembered and handed back on the next call.
    private val mangaCache = BoundedCache<String, SManga>(256)
    private val chapterCache = BoundedCache<String, NativeChapter>(4096)

    // --- listings ---

    override suspend fun getPopularNovels(page: Int): NovelsPage =
        io("getPopularManga") { native.getPopularManga(page) }.toNovelsPage()

    override suspend fun getLatestNovels(page: Int): NovelsPage {
        if (!supportsLatest) return getPopularNovels(page)
        return try {
            io("getLatestUpdates") { native.getLatestUpdates(page) }.toNovelsPage()
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "$name: latest feed not implemented, using popular", e)
            getPopularNovels(page)
        }
    }

    override suspend fun searchNovels(query: String, page: Int): NovelsPage =
        searchNovels(query, page, getFilterList())

    // --- filters ---

    /** A fresh filter list (sources must return new instances; callers mutate their `state`). */
    fun getFilterList(): FilterList =
        runCatching { native.getFilterList() }
            .onFailure { Log.w(TAG, "$name: getFilterList failed", it) }
            .getOrDefault(FilterList())

    /** Flattened, renderable view of [filters]. */
    fun describeFilters(filters: FilterList = getFilterList()): List<SourceFilterItem> = filters.describe()

    suspend fun searchNovels(query: String, page: Int, filters: FilterList): NovelsPage =
        io("getSearchManga") { native.getSearchManga(page, query, filters) }.toNovelsPage()

    // --- details / chapters ---

    override suspend fun getNovelDetails(url: String): SNovel {
        val base = mangaCache[url] ?: stubManga(url)
        val update = io("getMangaUpdate(details)") {
            native.getMangaUpdate(base, emptyList(), fetchDetails = true, fetchChapters = false)
        }
        mangaCache[url] = update.manga
        return update.manga.toSNovel(urlOverride = url)
    }

    override suspend fun getChapterList(novelUrl: String): List<SChapter> {
        val base = mangaCache[novelUrl] ?: stubManga(novelUrl)
        val update = io("getMangaUpdate(chapters)") {
            native.getMangaUpdate(base, emptyList(), fetchDetails = false, fetchChapters = true)
        }
        update.chapters.forEach { chapter ->
            runCatching { http?.prepareNewChapter(chapter, base) }
                .onFailure { Log.w(TAG, "$name: prepareNewChapter failed", it) }
            chapterCache[chapter.url] = chapter
        }
        val mapped = update.chapters.map { it.toSChapter() }
        // Mihon sources list newest first; Hibana's contract is oldest first (index 0 = first chapter).
        return if (isDescending(mapped)) mapped.asReversed() else mapped
    }

    /**
     * Manga chapters are images, so there is no source-provided text. The reader
     * renders pages instead, and text-to-speech OCRs those pages.
     */
    override suspend fun getChapterText(chapterUrl: String): ChapterText = ChapterText(emptyList())

    // --- pages / images ---

    override suspend fun getPages(chapterUrl: String): List<ImagePage> {
        val base = chapterCache[chapterUrl] ?: stubChapter(chapterUrl)
        return io("getPageList") { native.getPageList(base) }.mapIndexed { position, page ->
            ImagePage(
                // The index returned by sources is documented as ignored; position is authoritative.
                index = position,
                url = page.url,
                imageUrl = page.imageUrl?.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * Page URL vs image URL: `Page.imageUrl` is the image when present; otherwise the source must be
     * asked (`HttpSource.getImageUrl`) to turn `Page.url` into one. The previous behaviour of returning
     * `Page.url` as if it were an image was wrong for sources that use the two-step flow.
     */
    override suspend fun resolveImageUrl(page: ImagePage): String {
        page.imageUrl?.takeIf { it.isNotBlank() }?.let { return it }
        val source = http ?: return page.url
        val resolved = io("getImageUrl") { source.getImageUrl(Page(page.index, page.url)) }
        return resolved.ifBlank { page.url }
    }

    override suspend fun getPageUrls(chapterUrl: String): List<String> =
        getPages(chapterUrl).map { resolveImageUrl(it) }

    override val imageClient: OkHttpClient? get() = http?.client

    override val imageHeaders: Headers? get() = http?.headers

    /** Fetches the image through the source's own `getImage`, so `imageRequest` overrides apply. */
    override suspend fun openImage(page: ImagePage): Response? {
        val source = http ?: return null
        val imageUrl = resolveImageUrl(page)
        return io("getImage") { source.getImage(Page(page.index, page.url, imageUrl)) }
    }

    // --- helpers ---

    private suspend fun <T> io(what: String, block: suspend () -> T): T = try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // Failures used to be swallowed into empty results, which made a broken extension look empty.
        Log.e(TAG, "$name ($pkgId): $what failed", e)
        throw e
    }

    private fun stubManga(url: String): SManga = SManga.create().apply {
        this.url = url
        title = "" // lateinit in SMangaImpl; sources may read it
    }

    private fun stubChapter(url: String): NativeChapter = NativeChapter.create().apply {
        this.url = url
        name = "" // lateinit in SChapterImpl
    }

    private fun MangasPage.toNovelsPage(): NovelsPage {
        mangas.forEach { mangaCache[it.url] = it }
        return NovelsPage(mangas.map { it.toSNovel() }, hasNextPage)
    }

    private fun SManga.toSNovel(urlOverride: String? = null) = SNovel(
        url = urlOverride ?: url,
        title = runCatching { title }.getOrDefault(""),
        author = author,
        description = description,
        coverUrl = thumbnail_url,
        genres = getGenres().orEmpty(),
        status = when (status) {
            SManga.ONGOING -> NovelStatus.ONGOING
            SManga.COMPLETED, SManga.PUBLISHING_FINISHED -> NovelStatus.COMPLETED
            SManga.CANCELLED -> NovelStatus.CANCELLED
            SManga.ON_HIATUS -> NovelStatus.HIATUS
            else -> NovelStatus.UNKNOWN
        },
    )

    private fun NativeChapter.toSChapter() = SChapter(
        url = url,
        name = name,
        number = chapter_number.takeIf { it >= 0f } ?: parseChapterNumber(name),
        dateUpload = date_upload,
    )

    private fun isDescending(chapters: List<SChapter>): Boolean {
        val numbers = chapters.map { it.number }.filter { it >= 0f }
        if (numbers.size < 2) return false
        return numbers.first() > numbers.last()
    }

    private class BoundedCache<K, V>(private val max: Int) {
        private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
        }

        @Synchronized operator fun get(key: K): V? = map[key]

        @Synchronized operator fun set(key: K, value: V) {
            map[key] = value
        }
    }

    private companion object {
        const val TAG = "HibanaExt"

        val CHAPTER_NUMBER = Regex("""(?i)(?:ch(?:apter|\.)?|ep(?:isode)?|#)\s*([0-9]+(?:\.[0-9]+)?)""")

        fun parseChapterNumber(name: String): Float =
            CHAPTER_NUMBER.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
    }
}
