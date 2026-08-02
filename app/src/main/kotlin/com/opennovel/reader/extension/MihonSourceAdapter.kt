package com.opennovel.reader.extension

import com.opennovel.reader.source.HttpSource
import com.opennovel.reader.source.Source
import com.opennovel.reader.source.model.ChapterText
import com.opennovel.reader.source.model.NovelStatus
import com.opennovel.reader.source.model.NovelsPage
import com.opennovel.reader.source.model.SChapter
import com.opennovel.reader.source.model.SNovel
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.functions
import kotlin.reflect.jvm.isAccessible

/**
 * Bridges a loaded Tachiyomi/Mihon (or Manatan) source object onto Hibana's
 * [Source] contract by reflection, so we don't compile against the tachiyomi
 * extensions-lib. Static metadata (id/name/lang/baseUrl) reads directly; the
 * catalogue calls invoke the source's coroutine `getPopularManga` /
 * `getSearchManga` / `getMangaDetails` / `getChapterList` reflectively.
 *
 * Mihon/Manatan sources are *manga* sources: their chapters resolve to image
 * pages, not novel text, so [getChapterText] returns the page URLs as a note.
 * Any reflective miss (missing method, absent runtime class) degrades to an
 * empty result instead of crashing the host.
 */
class MihonSourceAdapter(
    private val native: Any,
    private val ecosystem: Ecosystem,
) : Source {

    override val id: Long =
        (native.read("id") as? Long) ?: HttpSource.deriveId(native.read("name") as? String ?: native.toString(), "all", 1)

    override val name: String = native.read("name") as? String ?: "Unknown"
    override val lang: String = native.read("lang") as? String ?: "all"
    override val baseUrl: String = native.read("baseUrl") as? String ?: ""

    override suspend fun getPopularNovels(page: Int): NovelsPage =
        native.callMangasPage("getPopularManga", page)

    override suspend fun searchNovels(query: String, page: Int): NovelsPage =
        runCatching {
            val filters = native.invokeSuspendOrNull("getFilterList")
                ?: native.read("getFilterList")
            val result = native.callSuspendByName("getSearchManga", page, query, filters)
            result.toNovelsPage()
        }.getOrDefault(NovelsPage(emptyList(), false))

    override suspend fun getNovelDetails(url: String): SNovel {
        val sManga = native.buildSManga(url) ?: return SNovel(url = url, title = url)
        val detailed = native.callSuspendByName("getMangaDetails", sManga) ?: sManga
        return detailed.toSNovel()
    }

    override suspend fun getChapterList(novelUrl: String): List<SChapter> {
        val sManga = native.buildSManga(novelUrl) ?: return emptyList()
        val list = native.callSuspendByName("getChapterList", sManga) as? List<*> ?: return emptyList()
        return list.mapNotNull { it?.toSChapter() }
    }

    override suspend fun getChapterText(chapterUrl: String): ChapterText =
        ChapterText(
            listOf(
                "This is a ${ecosystem.label} manga source — chapters are images, not text.",
                "Open it in an image reader; Hibana renders text novels.",
            ),
        )

    // --- reflection helpers ---

    private suspend fun Any.callMangasPage(method: String, page: Int): NovelsPage =
        runCatching { callSuspendByName(method, page).toNovelsPage() }
            .getOrDefault(NovelsPage(emptyList(), false))

    private suspend fun Any.callSuspendByName(method: String, vararg args: Any?): Any? {
        val fn = this::class.functions.firstOrNull { it.name == method } ?: return null
        fn.isAccessible = true
        return fn.callSuspend(this, *args)
    }

    private suspend fun Any.invokeSuspendOrNull(method: String): Any? =
        runCatching { callSuspendByName(method) }.getOrNull()

    private fun Any?.toNovelsPage(): NovelsPage {
        if (this == null) return NovelsPage(emptyList(), false)
        val mangas = (read("mangas") as? List<*>).orEmpty().mapNotNull { it?.toSNovel() }
        val hasNext = read("hasNextPage") as? Boolean ?: false
        return NovelsPage(mangas, hasNext)
    }

    private fun Any.toSNovel(): SNovel = SNovel(
        url = read("url") as? String ?: "",
        title = read("title") as? String ?: "",
        author = read("author") as? String,
        description = read("description") as? String,
        coverUrl = read("thumbnail_url") as? String,
        genres = (read("genre") as? String).orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() },
        status = NovelStatus.UNKNOWN,
    )

    private fun Any.toSChapter(): SChapter = SChapter(
        url = read("url") as? String ?: "",
        name = read("name") as? String ?: "",
        number = (read("chapter_number") as? Float) ?: -1f,
        dateUpload = (read("date_upload") as? Long) ?: 0L,
    )

    /** Build an empty SManga carrying just [url] to pass into details/chapter calls. */
    private fun Any.buildSManga(url: String): Any? = runCatching {
        val fn = this::class.functions.first { it.name == "getMangaDetails" }
        val paramType = fn.parameters[1].type.classifier as? kotlin.reflect.KClass<*> ?: return null
        val created = paramType.java.getMethod("create").invoke(null)
        created.javaClass.methods.firstOrNull { it.name == "setUrl" }?.invoke(created, url)
        created
    }.getOrNull()
}

/** Reflective property read: prefers a Kotlin/Java getter, falls back to the field. */
private fun Any?.read(name: String): Any? {
    if (this == null) return null
    val getter = "get" + name.replaceFirstChar { it.uppercase() }
    javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }
        ?.let { return runCatching { it.invoke(this) }.getOrNull() }
    return runCatching {
        javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(this)
    }.getOrNull()
}

/** Recognises + drives a Tachiyomi `SourceFactory` (has `createSources(): List<Source>`). */
object ReflectiveSource {
    fun isSourceFactory(instance: Any): Boolean =
        instance.javaClass.methods.any { it.name == "createSources" && it.parameterCount == 0 }

    fun createFromFactory(instance: Any): List<Any> =
        runCatching {
            (instance.javaClass.getMethod("createSources").invoke(instance) as? List<*>)
                ?.filterNotNull().orEmpty()
        }.getOrDefault(emptyList())
}
