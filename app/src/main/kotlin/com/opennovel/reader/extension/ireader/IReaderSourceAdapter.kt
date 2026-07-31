package com.opennovel.reader.extension.ireader

import com.opennovel.reader.extension.ExtensionInfo
import com.opennovel.reader.source.Source
import com.opennovel.reader.source.model.ChapterText
import com.opennovel.reader.source.model.NovelStatus
import com.opennovel.reader.source.model.NovelsPage
import com.opennovel.reader.source.model.SChapter
import com.opennovel.reader.source.model.SNovel
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KParameter
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Adapts a loaded `ireader.core.source.CatalogSource` instance onto this app's
 * [Source] contract. Because the app is intentionally decoupled from IReader's
 * API at compile time (the classes arrive from the extension's DexClassLoader at
 * runtime), all interop goes through Kotlin reflection:
 *
 *  - `kotlin.reflect.full.callSuspend` invokes the source's `suspend` functions
 *    (`getMangaList`, `getMangaDetails`, `getChapterList`, `getPageList`),
 *  - `primaryConstructor.callBy` builds `MangaInfo` / `ChapterInfo` with only the
 *    fields we set (the rest take their declared defaults),
 *  - member-property reads pull results back out.
 *
 * IReader's novel model maps cleanly:
 *  - `MangaInfo.key`      -> our novel/chapter url
 *  - `Text` page bodies   -> our [ChapterText] paragraphs (image pages are ignored)
 */
class IReaderSourceAdapter(
    private val delegate: Any,
    private val loader: ClassLoader,
    info: ExtensionInfo,
) : Source {

    override val id: Long = readProp(delegate, "id") as? Long
        ?: (("ireader/" + info.pkgId).hashCode().toLong() and Long.MAX_VALUE)
    override val name: String = readProp(delegate, "name") as? String ?: info.name
    override val lang: String = readProp(delegate, "lang") as? String ?: info.lang
    override val baseUrl: String = readProp(delegate, "baseUrl") as? String ?: ""

    // Model classes come from the extension's classloader, not ours.
    private val mangaInfoClass by lazy { loader.loadClass("ireader.core.source.model.MangaInfo").kotlin }
    private val chapterInfoClass by lazy { loader.loadClass("ireader.core.source.model.ChapterInfo").kotlin }
    private val titleFilterClass by lazy { loader.loadClass("ireader.core.source.model.Filter\$Title").kotlin }

    override suspend fun getPopularNovels(page: Int): NovelsPage {
        // getMangaList(sort: Listing?, page: Int) — null asks for the default listing.
        val fn = suspendFn("getMangaList") { firstValueParamSimpleName(it) == "Listing" }
            ?: return NovelsPage(emptyList(), false)
        val result = fn.callSuspend(delegate, null, page)
        return result.toNovelsPage()
    }

    override suspend fun searchNovels(query: String, page: Int): NovelsPage {
        val fn = suspendFn("getMangaList") { firstValueParamSimpleName(it) != "Listing" }
            ?: return NovelsPage(emptyList(), false)
        val filters = listOf(newTitleFilter(query))
        val result = fn.callSuspend(delegate, filters, page)
        return result.toNovelsPage()
    }

    override suspend fun getNovelDetails(url: String): SNovel {
        val fn = suspendFn("getMangaDetails") ?: return SNovel(url = url, title = url)
        val manga = newMangaInfo(key = url, title = "")
        val result = fn.callSuspend(delegate, manga, emptyList<Any?>())
        return result.toSNovel()
    }

    override suspend fun getChapterList(novelUrl: String): List<SChapter> {
        val fn = suspendFn("getChapterList") ?: return emptyList()
        val manga = newMangaInfo(key = novelUrl, title = "")
        val list = fn.callSuspend(delegate, manga, emptyList<Any?>()) as? List<*> ?: return emptyList()
        return list.mapNotNull { it?.toSChapter() }
    }

    override suspend fun getChapterText(chapterUrl: String): ChapterText {
        val fn = suspendFn("getPageList") ?: return ChapterText(emptyList())
        val chapter = newChapterInfo(key = chapterUrl, name = "")
        val pages = fn.callSuspend(delegate, chapter, emptyList<Any?>()) as? List<*> ?: return ChapterText(emptyList())
        val paragraphs = pages.mapNotNull { page ->
            if (page != null && page::class.simpleName == "Text") readProp(page, "text") as? String else null
        }.filter { it.isNotBlank() }
        return ChapterText(paragraphs)
    }

    // ---- reflection helpers ----

    private fun suspendFn(name: String, predicate: (kotlin.reflect.KFunction<*>) -> Boolean = { true }) =
        delegate::class.memberFunctions.firstOrNull { it.name == name && it.isSuspend && predicate(it) }

    private fun firstValueParamSimpleName(fn: kotlin.reflect.KFunction<*>): String? =
        fn.parameters.firstOrNull { it.kind == KParameter.Kind.VALUE }
            ?.type?.classifier?.let { (it as? kotlin.reflect.KClass<*>)?.simpleName }

    private fun newMangaInfo(key: String, title: String): Any {
        val ctor = mangaInfoClass.primaryConstructor!!
        val args = ctor.parameters
            .filter { it.name == "key" || it.name == "title" }
            .associateWith { if (it.name == "key") key else title }
        return ctor.callBy(args)
    }

    private fun newChapterInfo(key: String, name: String): Any {
        val ctor = chapterInfoClass.primaryConstructor!!
        val args = ctor.parameters
            .filter { it.name == "key" || it.name == "name" }
            .associateWith { if (it.name == "key") key else name }
        return ctor.callBy(args)
    }

    private fun newTitleFilter(query: String): Any {
        val instance = titleFilterClass.primaryConstructor!!.callBy(emptyMap())
        @Suppress("UNCHECKED_CAST")
        val valueProp = titleFilterClass.memberProperties
            .first { it.name == "value" } as KMutableProperty1<Any, Any?>
        valueProp.setter.call(instance, query)
        return instance
    }

    private fun Any?.toNovelsPage(): NovelsPage {
        if (this == null) return NovelsPage(emptyList(), false)
        val mangas = readProp(this, "mangas") as? List<*> ?: emptyList<Any?>()
        val hasNext = readProp(this, "hasNextPage") as? Boolean ?: false
        return NovelsPage(mangas.mapNotNull { it?.toSNovel() }, hasNext)
    }

    private fun Any.toSNovel(): SNovel = SNovel(
        url = readProp(this, "key") as? String ?: "",
        title = readProp(this, "title") as? String ?: "",
        author = (readProp(this, "author") as? String)?.ifBlank { null },
        description = (readProp(this, "description") as? String)?.ifBlank { null },
        coverUrl = (readProp(this, "cover") as? String)?.ifBlank { null },
        genres = (readProp(this, "genres") as? List<*>)?.filterIsInstance<String>().orEmpty(),
        status = mapStatus(readProp(this, "status") as? Long ?: 0L),
    )

    private fun Any.toSChapter(): SChapter = SChapter(
        url = readProp(this, "key") as? String ?: "",
        name = readProp(this, "name") as? String ?: "",
        number = readProp(this, "number") as? Float ?: -1f,
        dateUpload = readProp(this, "dateUpload") as? Long ?: 0L,
    )

    private fun mapStatus(status: Long): NovelStatus = when (status) {
        1L -> NovelStatus.ONGOING
        2L, 4L -> NovelStatus.COMPLETED
        5L -> NovelStatus.CANCELLED
        6L -> NovelStatus.HIATUS
        else -> NovelStatus.UNKNOWN
    }

    private fun readProp(obj: Any, prop: String): Any? = runCatching {
        obj::class.memberProperties.firstOrNull { it.name == prop }?.getter?.call(obj)
    }.getOrNull()
}
