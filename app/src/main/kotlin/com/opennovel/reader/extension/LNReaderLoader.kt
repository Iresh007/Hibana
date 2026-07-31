package com.opennovel.reader.extension

import com.opennovel.reader.source.Source
import com.opennovel.reader.source.model.ChapterText
import com.opennovel.reader.source.model.NovelsPage
import com.opennovel.reader.source.model.SChapter
import com.opennovel.reader.source.model.SNovel

/**
 * Adapter for the **LNReader** ecosystem.
 *
 * LNReader plugins are JavaScript modules published to a plugin repository
 * (e.g. the LNReader-plugins repo). Each plugin exports an object with
 * `popularNovels`, `searchNovels`, `parseNovel`, and `parseChapter` functions
 * plus metadata (`id`, `name`, `site`, `version`, `icon`).
 *
 * To run these unmodified we embed a JavaScript engine and bridge the browser
 * APIs the plugins expect:
 *  - a `fetch`/`fetchApi` implementation backed by OkHttp,
 *  - `cheerio`/`htmlparser2` style DOM parsing backed by Jsoup,
 *  - `dayjs`/`qs` shims as needed.
 *
 * Recommended engine: QuickJS (via `quickjs-android` / `quack`) for a small,
 * fast, sandboxed runtime; Rhino is a pure-JVM fallback but slower and lacks
 * modern JS. This class is the integration seam; wiring the engine is the
 * remaining implementation work (see README → "Extension compatibility").
 */
class LNReaderLoader(
    private val jsRuntime: JsRuntime,
) : ExtensionLoader {

    override val ecosystem = Ecosystem.LNREADER

    override suspend fun listAvailable(repoUrl: String): List<ExtensionInfo> {
        // Fetch the repo's plugins.min.json manifest and map entries.
        return jsRuntime.fetchPluginManifest(repoUrl).map {
            ExtensionInfo(
                pkgId = it.id,
                name = it.name,
                lang = it.lang,
                versionName = it.version,
                ecosystem = Ecosystem.LNREADER,
                installed = false,
                artifact = it.url,
            )
        }
    }

    override suspend fun listInstalled(): List<ExtensionInfo> = jsRuntime.installedPlugins()

    override suspend fun load(info: ExtensionInfo): List<Source> {
        val handle = jsRuntime.loadPlugin(info.artifact)
        return listOf(LNReaderSource(handle, info))
    }
}

/** A [Source] backed by a loaded LNReader JS plugin handle. */
private class LNReaderSource(
    private val handle: JsPluginHandle,
    info: ExtensionInfo,
) : Source {
    override val id = ("lnreader/" + info.pkgId).hashCode().toLong() and Long.MAX_VALUE
    override val name = info.name
    override val lang = info.lang
    override val baseUrl = handle.site

    override suspend fun getPopularNovels(page: Int) = handle.call<NovelsPage>("popularNovels", page)
    override suspend fun searchNovels(query: String, page: Int) =
        handle.call<NovelsPage>("searchNovels", query, page)
    override suspend fun getNovelDetails(url: String) = handle.call<SNovel>("parseNovel", url)
    override suspend fun getChapterList(novelUrl: String) =
        handle.call<List<SChapter>>("parseNovel.chapters", novelUrl)
    override suspend fun getChapterText(chapterUrl: String) =
        handle.call<ChapterText>("parseChapter", chapterUrl)
}

/**
 * Minimal contract the embedded JS engine must satisfy. Implement with QuickJS
 * or Rhino; marshalling between JS objects and our data classes happens here.
 */
interface JsRuntime {
    suspend fun fetchPluginManifest(repoUrl: String): List<JsPluginMeta>
    suspend fun installedPlugins(): List<ExtensionInfo>
    suspend fun loadPlugin(artifactUrl: String): JsPluginHandle
}

data class JsPluginMeta(val id: String, val name: String, val lang: String, val version: String, val url: String)

interface JsPluginHandle {
    val site: String
    suspend fun <T> call(fn: String, vararg args: Any?): T
}
