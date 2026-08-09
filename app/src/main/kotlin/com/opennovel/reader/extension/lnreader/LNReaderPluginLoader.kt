package com.opennovel.reader.extension.lnreader

import android.content.Context
import com.opennovel.reader.extension.Ecosystem
import com.opennovel.reader.extension.ExtensionInfo
import com.opennovel.reader.extension.ExtensionLoader
import com.opennovel.reader.source.Source
import com.opennovel.reader.source.model.ChapterText
import com.opennovel.reader.source.model.NovelStatus
import com.opennovel.reader.source.model.NovelsPage
import com.opennovel.reader.source.model.SChapter
import com.opennovel.reader.source.model.SNovel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.File

/**
 * Loads **LNReader** JavaScript plugins and exposes them as [Source]s.
 *
 * Plugins are published as minified ES5 CommonJS modules listed in a repo
 * manifest (`plugins.min.json`: `{id,name,site,lang,version,url,iconUrl}`).
 * Installing one downloads its `.js` to app storage; loading evaluates it in
 * [RhinoJsRuntime] and wraps the exported plugin object.
 */
class LNReaderPluginLoader(
    private val context: Context,
    private val client: OkHttpClient,
    private val runtime: RhinoJsRuntime = RhinoJsRuntime(client),
) : ExtensionLoader {

    override val ecosystem = Ecosystem.LNREADER

    private val pluginDir: File
        get() = File(context.filesDir, "lnreader_plugins").apply { mkdirs() }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun listAvailable(repoUrl: String): List<ExtensionInfo> = withContext(Dispatchers.IO) {
        val body = runCatching {
            client.newCall(Request.Builder().url(repoUrl).build()).execute()
                .use { it.body?.string().orEmpty() }
        }.getOrDefault("")
        if (body.isBlank()) return@withContext emptyList()

        runCatching {
            (json.parseToJsonElement(body) as JsonArray).mapNotNull { entry ->
                val o = entry.jsonObject
                val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                ExtensionInfo(
                    pkgId = id,
                    name = o["name"]?.jsonPrimitive?.content ?: id,
                    lang = o["lang"]?.jsonPrimitive?.content ?: "all",
                    versionName = o["version"]?.jsonPrimitive?.content ?: "?",
                    ecosystem = Ecosystem.LNREADER,
                    installed = pluginFile(id).exists(),
                    artifact = o["url"]?.jsonPrimitive?.content.orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun listInstalled(): List<ExtensionInfo> = withContext(Dispatchers.IO) {
        pluginDir.listFiles { f -> f.extension == "js" }.orEmpty().map { file ->
            val id = file.nameWithoutExtension
            ExtensionInfo(
                pkgId = id,
                name = id,
                lang = "all",
                versionName = "?",
                ecosystem = Ecosystem.LNREADER,
                installed = true,
                artifact = file.absolutePath,
            )
        }
    }

    /** Downloads a plugin's JS to app storage so it can be loaded offline. */
    suspend fun install(info: ExtensionInfo): Boolean = withContext(Dispatchers.IO) {
        if (info.artifact.isBlank()) return@withContext false
        runCatching {
            client.newCall(Request.Builder().url(info.artifact).build()).execute().use { resp ->
                val src = resp.body?.string().orEmpty()
                if (src.isBlank()) return@withContext false
                pluginFile(info.pkgId).writeText(src)
            }
            true
        }.getOrDefault(false)
    }

    override suspend fun load(info: ExtensionInfo): List<Source> = withContext(Dispatchers.IO) {
        val file = if (info.artifact.endsWith(".js") && File(info.artifact).exists()) {
            File(info.artifact)
        } else {
            pluginFile(info.pkgId)
        }
        if (!file.exists()) return@withContext emptyList()

        val plugin = runtime.evaluatePlugin(file.readText(), info.pkgId)
            ?: return@withContext emptyList()
        listOf(LNReaderSource(plugin, runtime, info))
    }

    private fun pluginFile(id: String) = File(pluginDir, "$id.js")

    companion object {
        /** Official plugin repository manifest. */
        const val DEFAULT_REPO =
            "https://raw.githubusercontent.com/lnreader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json"
    }
}

/**
 * A [Source] backed by a loaded LNReader plugin object. Marshals between JS
 * values and Hibana's models; anything the plugin fails to return degrades to an
 * empty result rather than propagating a JS error into the app.
 */
private class LNReaderSource(
    private val plugin: Scriptable,
    private val runtime: RhinoJsRuntime,
    private val info: ExtensionInfo,
) : Source {

    override val id: Long = ("lnreader/" + info.pkgId).hashCode().toLong() and Long.MAX_VALUE
    override val name: String = plugin.stringOrNull("name") ?: info.name
    override val lang: String = info.lang
    override val baseUrl: String = plugin.stringOrNull("site").orEmpty()

    override suspend fun getPopularNovels(page: Int): NovelsPage = withContext(Dispatchers.IO) {
        val result = runtime.callMethod(plugin, "popularNovels", page)
        NovelsPage(result.toNovelList(), hasNextPage = result.toNovelList().isNotEmpty())
    }

    override suspend fun searchNovels(query: String, page: Int): NovelsPage = withContext(Dispatchers.IO) {
        val result = runtime.callMethod(plugin, "searchNovels", query, page)
        NovelsPage(result.toNovelList(), hasNextPage = false)
    }

    override suspend fun getNovelDetails(url: String): SNovel = withContext(Dispatchers.IO) {
        val result = runtime.callMethod(plugin, "parseNovel", url) as? Scriptable
            ?: return@withContext SNovel(url = url, title = url)
        result.toSNovel(fallbackPath = url)
    }

    override suspend fun getChapterList(novelUrl: String): List<SChapter> = withContext(Dispatchers.IO) {
        val novel = runtime.callMethod(plugin, "parseNovel", novelUrl) as? Scriptable
            ?: return@withContext emptyList()
        val chapters = ScriptableObject.getProperty(novel, "chapters") as? NativeArray
            ?: return@withContext emptyList()
        chapters.toKotlinList().filterIsInstance<Scriptable>().mapIndexed { index, ch ->
            SChapter(
                url = ch.stringOrNull("path") ?: ch.stringOrNull("url").orEmpty(),
                name = ch.stringOrNull("name") ?: "Chapter ${index + 1}",
                number = (index + 1).toFloat(),
            )
        }
    }

    override suspend fun getChapterText(chapterUrl: String): ChapterText = withContext(Dispatchers.IO) {
        // parseChapter returns an HTML string; convert to readable paragraphs.
        val html = runtime.callMethod(plugin, "parseChapter", chapterUrl) as? String
            ?: return@withContext ChapterText(emptyList())
        val doc = org.jsoup.Jsoup.parse(html)
        val paragraphs = doc.select("p").map { it.text().trim() }.filter { it.isNotBlank() }
        ChapterText(
            paragraphs.ifEmpty {
                doc.body().wholeText().split("\n").map { it.trim() }.filter { it.isNotBlank() }
            },
        )
    }

    private fun Any?.toNovelList(): List<SNovel> {
        val array = this as? NativeArray ?: return emptyList()
        return array.toKotlinList().filterIsInstance<Scriptable>().map { it.toSNovel() }
    }

    private fun Scriptable.toSNovel(fallbackPath: String? = null): SNovel = SNovel(
        url = stringOrNull("path") ?: stringOrNull("url") ?: fallbackPath.orEmpty(),
        title = stringOrNull("name") ?: stringOrNull("title").orEmpty(),
        author = stringOrNull("author"),
        description = stringOrNull("summary") ?: stringOrNull("description"),
        coverUrl = stringOrNull("cover"),
        genres = stringOrNull("genres")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
        status = when (stringOrNull("status")?.lowercase()) {
            "ongoing" -> NovelStatus.ONGOING
            "completed", "publishingfinished" -> NovelStatus.COMPLETED
            "cancelled" -> NovelStatus.CANCELLED
            "onhiatus" -> NovelStatus.HIATUS
            else -> NovelStatus.UNKNOWN
        },
    )
}
