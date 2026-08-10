package com.opennovel.reader.extension

import com.opennovel.reader.source.Source
import com.opennovel.reader.source.model.ChapterText
import com.opennovel.reader.source.model.NovelStatus
import com.opennovel.reader.source.model.NovelsPage
import com.opennovel.reader.source.model.SChapter
import com.opennovel.reader.source.model.SNovel
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.Source as TachiyomiSource

/**
 * Bridges a loaded Tachiyomi/Mihon (or Manatan) source onto Hibana's [Source]
 * contract using the host source-api runtime. Mihon/Manatan sources are *manga*
 * sources — chapters resolve to image pages, not novel text — so [getChapterText]
 * returns a note.
 */
class MihonSourceAdapter(
    private val native: TachiyomiSource,
    private val ecosystem: Ecosystem,
) : Source, com.opennovel.reader.source.ImageChapterSource {

    private val catalogue: CatalogueSource? = native as? CatalogueSource

    /**
     * Non-null when the extension declares its own settings screen. The adapter
     * is the only object that still holds the extension instance once loading is
     * done, so the preferences UI has to reach it through here.
     */
    val configurable: ConfigurableSource? = native as? ConfigurableSource

    override val id: Long = native.id
    override val name: String = native.name
    override val lang: String = catalogue?.lang ?: native.lang.ifBlank { "all" }
    override val baseUrl: String = (native as? HttpSource)?.baseUrl ?: ""

    override val supportsLatest: Boolean = catalogue?.supportsLatest ?: false

    override suspend fun getPopularNovels(page: Int): NovelsPage =
        catalogue?.getPopularManga(page)?.toNovelsPage() ?: NovelsPage(emptyList(), false)

    override suspend fun getLatestNovels(page: Int): NovelsPage {
        val cat = catalogue ?: return NovelsPage(emptyList(), false)
        // Sources advertising no latest feed throw from the default impl.
        if (!cat.supportsLatest) return getPopularNovels(page)
        return runCatching { cat.getLatestUpdates(page).toNovelsPage() }
            .getOrElse { getPopularNovels(page) }
    }

    override suspend fun searchNovels(query: String, page: Int): NovelsPage {
        val cat = catalogue ?: return NovelsPage(emptyList(), false)
        val filters = runCatching { cat.getFilterList() }.getOrDefault(FilterList())
        return cat.getSearchManga(page, query, filters).toNovelsPage()
    }

    override suspend fun getNovelDetails(url: String): SNovel {
        val stub = SManga.create().apply { this.url = url }
        val details = runCatching { native.getMangaDetails(stub) }.getOrDefault(stub)
        return details.toSNovel()
    }

    override suspend fun getChapterList(novelUrl: String): List<SChapter> {
        val stub = SManga.create().apply { url = novelUrl }
        return runCatching { native.getChapterList(stub) }.getOrDefault(emptyList())
            .map { it.toSChapter() }
    }

    /**
     * Manga chapters are images, so there is no source-provided text. The reader
     * renders [getPageUrls] instead, and text-to-speech OCRs those pages.
     */
    override suspend fun getChapterText(chapterUrl: String): ChapterText = ChapterText(emptyList())

    override suspend fun getPageUrls(chapterUrl: String): List<String> {
        val stub = eu.kanade.tachiyomi.source.model.SChapter.create().apply { url = chapterUrl }
        val pages = runCatching { native.getPageList(stub) }.getOrDefault(emptyList())
        return pages.mapNotNull { page ->
            page.imageUrl?.takeIf { it.isNotBlank() }
                ?: page.url.takeIf { it.isNotBlank() }
        }
    }

    private fun MangasPage.toNovelsPage() = NovelsPage(mangas.map { it.toSNovel() }, hasNextPage)

    private fun SManga.toSNovel() = SNovel(
        url = url,
        title = title,
        author = author,
        description = description,
        coverUrl = thumbnail_url,
        genres = genre.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() },
        status = when (status) {
            SManga.ONGOING -> NovelStatus.ONGOING
            SManga.COMPLETED, SManga.PUBLISHING_FINISHED -> NovelStatus.COMPLETED
            SManga.CANCELLED -> NovelStatus.CANCELLED
            SManga.ON_HIATUS -> NovelStatus.HIATUS
            else -> NovelStatus.UNKNOWN
        },
    )

    private fun eu.kanade.tachiyomi.source.model.SChapter.toSChapter() = SChapter(
        url = url,
        name = name,
        number = chapter_number,
        dateUpload = date_upload,
    )
}
