package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.asJsoup
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Faithful host runtime of the Tachiyomi extensions-lib `ParsedHttpSource`. Turns
 * the HTML parse hooks into CSS-selector methods extensions implement.
 */
abstract class ParsedHttpSource : HttpSource() {

    protected abstract fun popularMangaSelector(): String
    protected abstract fun popularMangaFromElement(element: Element): SManga
    protected abstract fun popularMangaNextPageSelector(): String?

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector()).map { popularMangaFromElement(it) }
        val hasNext = popularMangaNextPageSelector()?.let { document.selectFirst(it) != null } ?: false
        return MangasPage(mangas, hasNext)
    }

    protected abstract fun searchMangaSelector(): String
    protected abstract fun searchMangaFromElement(element: Element): SManga
    protected abstract fun searchMangaNextPageSelector(): String?

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(searchMangaSelector()).map { searchMangaFromElement(it) }
        val hasNext = searchMangaNextPageSelector()?.let { document.selectFirst(it) != null } ?: false
        return MangasPage(mangas, hasNext)
    }

    protected open fun latestUpdatesSelector(): String = throw UnsupportedOperationException("Not used")
    protected open fun latestUpdatesFromElement(element: Element): SManga = throw UnsupportedOperationException("Not used")
    protected open fun latestUpdatesNextPageSelector(): String? = throw UnsupportedOperationException("Not used")

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(latestUpdatesSelector()).map { latestUpdatesFromElement(it) }
        val hasNext = latestUpdatesNextPageSelector()?.let { document.selectFirst(it) != null } ?: false
        return MangasPage(mangas, hasNext)
    }

    protected abstract fun mangaDetailsParse(document: Document): SManga
    override fun mangaDetailsParse(response: Response): SManga = mangaDetailsParse(response.asJsoup())

    protected abstract fun chapterListSelector(): String
    protected abstract fun chapterFromElement(element: Element): SChapter
    override fun chapterListParse(response: Response): List<SChapter> =
        response.asJsoup().select(chapterListSelector()).map { chapterFromElement(it) }

    protected abstract fun pageListParse(document: Document): List<Page>
    override fun pageListParse(response: Response): List<Page> = pageListParse(response.asJsoup())

    protected abstract fun imageUrlParse(document: Document): String
    override fun imageUrlParse(response: Response): String = imageUrlParse(response.asJsoup())
}
