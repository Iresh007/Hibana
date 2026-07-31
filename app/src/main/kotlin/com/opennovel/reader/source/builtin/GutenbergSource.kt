package com.opennovel.reader.source.builtin

import com.opennovel.reader.source.HttpSource
import com.opennovel.reader.source.model.ChapterText
import com.opennovel.reader.source.model.NovelStatus
import com.opennovel.reader.source.model.NovelsPage
import com.opennovel.reader.source.model.SChapter
import com.opennovel.reader.source.model.SNovel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A fully-legal built-in source backed by Project Gutenberg via the public
 * Gutendex API (https://gutendex.com). Serves as the reference implementation
 * for extension authors and gives the app real content out of the box.
 *
 * Each book is exposed as a single "Full text" chapter (Gutenberg plain-text
 * books are not reliably chapterized); the reader + TTS handle long bodies.
 */
class GutenbergSource(client: OkHttpClient) : HttpSource(client) {

    override val name = "Project Gutenberg"
    override val lang = "en"
    override val baseUrl = "https://gutendex.com"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getPopularNovels(page: Int): NovelsPage =
        query("$baseUrl/books/?page=$page")

    override suspend fun searchNovels(query: String, page: Int): NovelsPage =
        query("$baseUrl/books/?search=${query.trim().replace(" ", "%20")}&page=$page")

    private suspend fun query(url: String): NovelsPage = withContext(Dispatchers.IO) {
        val body = get(url)
        val root = json.parseToJsonElement(body).jsonObject
        val hasNext = root["next"]?.jsonPrimitive?.contentOrNullSafe() != null
        val novels = root["results"]!!.jsonArray.map { el ->
            val o = el.jsonObject
            val gid = o["id"]!!.jsonPrimitive.content
            val authors = o["authors"]?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
                ?.joinToString(", ")
            val cover = o["formats"]?.jsonObject
                ?.get("image/jpeg")?.jsonPrimitive?.contentOrNullSafe()
            val genres = o["subjects"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNullSafe() }
                ?.take(6).orEmpty()
            SNovel(
                url = gid, // book id; details fetch reuses it
                title = o["title"]?.jsonPrimitive?.content ?: "Untitled",
                author = authors,
                coverUrl = cover,
                genres = genres,
                status = NovelStatus.COMPLETED,
            )
        }
        NovelsPage(novels, hasNext)
    }

    override suspend fun getNovelDetails(url: String): SNovel = withContext(Dispatchers.IO) {
        val o = json.parseToJsonElement(get("$baseUrl/books/$url")).jsonObject
        val authors = o["authors"]?.jsonArray
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }?.joinToString(", ")
        SNovel(
            url = url,
            title = o["title"]?.jsonPrimitive?.content ?: "Untitled",
            author = authors,
            description = o["subjects"]?.jsonArray?.joinToString("; ") { it.jsonPrimitive.content },
            coverUrl = o["formats"]?.jsonObject?.get("image/jpeg")?.jsonPrimitive?.contentOrNullSafe(),
            genres = o["bookshelves"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            status = NovelStatus.COMPLETED,
        )
    }

    override suspend fun getChapterList(novelUrl: String): List<SChapter> =
        listOf(SChapter(url = novelUrl, name = "Full text", number = 1f))

    override suspend fun getChapterText(chapterUrl: String): ChapterText = withContext(Dispatchers.IO) {
        val o = json.parseToJsonElement(get("$baseUrl/books/$chapterUrl")).jsonObject
        val formats = o["formats"]!!.jsonObject
        val textUrl = formats.entries.firstOrNull {
            it.key.startsWith("text/plain")
        }?.value?.jsonPrimitive?.content ?: error("No plain-text format available")
        val raw = get(textUrl)
        // Gutenberg files wrap the work in boilerplate; trim to the actual body.
        val start = raw.indexOf("*** START").let { if (it >= 0) raw.indexOf('\n', it) + 1 else 0 }
        val end = raw.indexOf("*** END").let { if (it >= 0) it else raw.length }
        val body = raw.substring(start.coerceIn(0, raw.length), end.coerceIn(start, raw.length))
        val paragraphs = body.split(Regex("\\n\\s*\\n"))
            .map { it.replace('\n', ' ').trim() }
            .filter { it.isNotBlank() }
        ChapterText(paragraphs)
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use {
            if (!it.isSuccessful) error("HTTP ${it.code}")
            return it.body!!.string()
        }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        if (this.toString() == "null") null else content
}
