package com.opennovel.reader.backup

import com.opennovel.reader.data.db.CategoryDao
import com.opennovel.reader.data.db.ChapterDao
import com.opennovel.reader.data.db.ChapterEntity
import com.opennovel.reader.data.db.ContentType
import com.opennovel.reader.data.db.HistoryDao
import com.opennovel.reader.data.db.NovelDao
import com.opennovel.reader.data.db.NovelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Imports Manatan `.manatanbk` backups. See [ManatanContainer] for the file
 * layout, which was reverse engineered from a real export.
 *
 * Two kinds of data are read:
 *
 *  1. The novel library — the key/value records (`metadata:`, `progress:`,
 *     `category:`). Entries become [ContentType.NOVEL].
 *  2. The manga library — the `manga`, `chapters`, `categories` and
 *     `mangaCategories` database tables. Entries become [ContentType.COMIC].
 *
 * Everything else in the file (anime tables, extensions, sources, tracker
 * records, preferences, the immersion archive, download queue) is stepped over
 * without being decoded.
 *
 * Source mapping: Manatan manga carry the same 64-bit source id Mihon computes
 * for the same extension (checked against a Mihon backup of the same library:
 * every shared source id had an identical name), so ids are used as-is and the
 * entry is usable as soon as the matching extension is installed in Hibana.
 * Sources with no counterpart (Aidoku-based, Manatan-native, novel sources) keep
 * their id and simply stay unmatched until the entry is migrated.
 *
 * Chapter ordering: Manatan's `source_order` is oldest-first and 1-based; Hibana
 * stores the order a source returns, which for Mihon sources is newest-first, so
 * manga chapters are renumbered newest-first. Novel chapters keep their
 * table-of-contents order (oldest-first), as Hibana's novel sources do.
 *
 * Memory: the file is read twice from a temporary copy — once for the small
 * tables and novel records, once for `chapters` (decoded exactly once, one row
 * at a time) — so the ~77 MB inflated size never sits in memory.
 */
class ManatanBackupImporter internal constructor(private val store: BackupStore) {

    constructor(
        novelDao: NovelDao,
        chapterDao: ChapterDao,
        categoryDao: CategoryDao,
        historyDao: HistoryDao? = null,
    ) : this(RoomBackupStore(novelDao, chapterDao, categoryDao, historyDao, null))

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Spools [input] to a temporary file (it can only be read once, and the
     * import needs two passes) and imports from it.
     *
     * [includeReadNonLibrary] also stores manga that are not in the library but
     * have read progress, as not-in-library entries, so the progress survives.
     */
    suspend fun import(input: InputStream, includeReadNonLibrary: Boolean = true): ImportResult =
        withContext(Dispatchers.IO) {
            val tmp = File.createTempFile("manatan-import", ".bk")
            try {
                input.use { src -> tmp.outputStream().use { src.copyTo(it) } }
                importFrom({ FileInputStream(tmp) }, includeReadNonLibrary)
            } finally {
                tmp.delete()
            }
        }

    /** Imports from a re-openable source; [open] is called once per pass. */
    suspend fun importFrom(open: () -> InputStream, includeReadNonLibrary: Boolean = true): ImportResult =
        withContext(Dispatchers.IO) { run(open, includeReadNonLibrary) }

    // --- pass 1 + 2 ---

    private class Pass1 {
        val kv = LinkedHashMap<String, String>()
        val manga = ArrayList<Map<String, Any?>>()
        val categoryRows = ArrayList<Map<String, Any?>>()
        val mangaCategoryRows = ArrayList<Map<String, Any?>>()
    }

    private suspend fun run(open: () -> InputStream, includeReadNonLibrary: Boolean): ImportResult {
        val p1 = Pass1()
        open().use { stream ->
            ManatanContainer.scan(
                stream,
                onKv = { k, v -> p1.kv[k] = v },
                tables = mapOf(
                    "manga" to { row: Map<String, Any?> -> p1.manga.add(row); Unit },
                    "categories" to { row: Map<String, Any?> -> p1.categoryRows.add(row); Unit },
                    "mangaCategories" to { row: Map<String, Any?> -> p1.mangaCategoryRows.add(row); Unit },
                ),
            )
        }

        val wanted = HashSet<Long>()
        p1.manga.forEach { row ->
            val id = row.long("id") ?: return@forEach
            if (includeReadNonLibrary || row.truthy("in_library")) wanted.add(id)
        }
        val chaptersByManga = HashMap<Long, MutableList<ManatanChapter>>()
        if (wanted.isNotEmpty()) {
            open().use { stream ->
                ManatanContainer.scan(
                    stream,
                    onKv = null,
                    tables = mapOf(
                        "chapters" to { row: Map<String, Any?> ->
                            val mangaId = row.long("manga_id")
                            if (mangaId != null && mangaId in wanted) {
                                toChapter(row)?.let { chaptersByManga.getOrPut(mangaId) { ArrayList() }.add(it) }
                            }
                            Unit
                        },
                    ),
                )
            }
        }

        var novels = 0
        var chapters = 0
        var notInLibrary = 0
        var skipped = 0
        val historyByNovel = store.history().associateBy { it.novelId }

        // Categories: novel shelves from `category:` records, manga shelves from the table.
        val localCategory = HashMap<String, Long>() // "novel:<id>" / "manga:<id>" -> local id
        var categoryCount = 0
        p1.kv.forEach { (key, raw) ->
            if (!key.startsWith("category:")) return@forEach
            val obj = raw.asObject() ?: return@forEach
            val name = obj.string("name")?.takeIf { it.isNotBlank() } ?: return@forEach
            val externalId = obj.string("id") ?: key.removePrefix("category:")
            localCategory["novel:$externalId"] = store.ensureCategory(name, obj.int("order") ?: 0)
            categoryCount++
        }
        // Column names of the manga category tables are UNVERIFIED: the sample
        // export had both tables empty. Read defensively and skip on mismatch.
        p1.categoryRows.forEach { row ->
            val id = row.long("id") ?: return@forEach
            val name = row.str("name")?.takeIf { it.isNotBlank() } ?: return@forEach
            localCategory["manga:$id"] = store.ensureCategory(name, (row.long("order") ?: row.long("sort_order") ?: 0L).toInt())
            categoryCount++
        }
        val categoriesByManga = HashMap<Long, MutableList<Long>>()
        p1.mangaCategoryRows.forEach { row ->
            val m = row.long("manga_id") ?: return@forEach
            val c = row.long("category_id") ?: return@forEach
            localCategory["manga:$c"]?.let { categoriesByManga.getOrPut(m) { ArrayList() }.add(it) }
        }

        // --- manga ---
        for (row in p1.manga) {
            val mangaId = row.long("id")
            val sourceId = row.long("source_id")
            val url = row.str("url")
            val title = (row.str("title_override") ?: row.str("title"))?.takeIf { it.isNotBlank() }
            if (mangaId == null || sourceId == null || url == null || title == null) { skipped++; continue }

            val inLibrary = row.truthy("in_library")
            val list = chaptersByManga.remove(mangaId).orEmpty()
            val hasProgress = list.any { it.read || it.bookmark || it.lastPageRead > 0 }
            if (!inLibrary && !(includeReadNonLibrary && hasProgress)) { skipped++; continue }

            // Oldest-first 1-based `source_order` -> newest-first index.
            val ordered = list.sortedBy { it.sourceOrder }
            val n = ordered.size
            val entities = ordered.mapIndexed { i, c ->
                ChapterEntity(
                    novelId = 0,
                    url = c.url,
                    name = c.name,
                    number = c.number,
                    dateUpload = c.dateUploadMs,
                    dateFetch = c.fetchedSec * 1000,
                    read = c.read,
                    bookmark = c.bookmark,
                    lastReadOffset = if (c.read) 1f else 0f,
                    sourceOrder = n - 1 - i,
                )
            }
            val latest = list.filter { it.lastReadSec > 0 }.maxByOrNull { it.lastReadSec }

            val novelId = persist(
                sourceId = sourceId,
                url = url,
                chapters = entities,
                categoryIds = categoriesByManga[mangaId].orEmpty(),
                lastReadUrl = latest?.url,
                lastReadAt = (latest?.lastReadSec ?: 0L) * 1000,
                historyByNovel = historyByNovel,
            ) { existing ->
                val base = existing ?: NovelEntity(sourceId = sourceId, url = url, title = title)
                base.copy(
                    title = title,
                    author = row.str("author")?.takeIf { it.isNotBlank() }
                        ?: row.str("artist")?.takeIf { it.isNotBlank() } ?: base.author,
                    description = row.str("description")?.takeIf { it.isNotBlank() } ?: base.description,
                    coverUrl = row.str("thumbnail_url")?.takeIf { it.isNotBlank() } ?: base.coverUrl,
                    genres = parseGenres(row.str("genre")).ifEmpty { listOfNotNull(base.genres.takeIf { it.isNotEmpty() }) }
                        .joinToString(","),
                    status = mapStatus(row.str("status")),
                    inLibrary = inLibrary || base.inLibrary,
                    dateAdded = when {
                        base.inLibrary && base.dateAdded > 0 -> base.dateAdded
                        inLibrary -> (row.long("in_library_at") ?: 0L).let { if (it > 0) it * 1000 else System.currentTimeMillis() }
                        else -> base.dateAdded
                    },
                    contentType = if (ContentType.from(base.contentType) == ContentType.UNKNOWN) ContentType.COMIC.name else base.contentType,
                )
            }
            if (novelId != null) {
                novels++
                chapters += entities.size
                if (!inLibrary) notInLibrary++
            }
        }

        // --- novels ---
        for ((key, raw) in p1.kv) {
            if (!key.startsWith("metadata:")) continue
            val obj = raw.asObject()
            val parsed = obj?.let { parseNovel(key.removePrefix("metadata:"), it) }
            if (obj == null || parsed == null) { skipped++; continue }

            val readKeys = obj.keySet("readChapterKeys")
            val bookmarkKeys = obj.keySet("bookmarkedChapterKeys")
            val entities = parsed.toc.map { t ->
                ChapterEntity(
                    novelId = 0,
                    url = t.key,
                    name = t.label,
                    number = (t.index + 1).toFloat(),
                    dateUpload = t.dateUploaded,
                    dateFetch = 0L,
                    read = t.key in readKeys,
                    bookmark = t.key in bookmarkKeys,
                    lastReadOffset = if (t.key in readKeys) 1f else 0f,
                    sourceOrder = t.index,
                )
            }
            val progress = p1.kv["progress:${parsed.entryId}"]?.asObject()
            val position = progress?.get("position") as? JsonObject
            val lastKey = position?.string("chapterKey")
            val lastAt = progress?.long("lastRead")?.takeIf { it > 0 } ?: progress?.long("lastModified") ?: 0L

            val novelId = persist(
                sourceId = parsed.sourceId,
                url = parsed.url,
                chapters = entities,
                categoryIds = parsed.categoryIds.mapNotNull { localCategory["novel:$it"] },
                lastReadUrl = lastKey,
                lastReadAt = lastAt,
                historyByNovel = historyByNovel,
            ) { existing ->
                val base = existing ?: NovelEntity(sourceId = parsed.sourceId, url = parsed.url, title = parsed.title)
                base.copy(
                    title = parsed.title,
                    author = parsed.author ?: base.author,
                    description = parsed.description ?: base.description,
                    coverUrl = parsed.coverUrl ?: base.coverUrl,
                    genres = if (parsed.tags.isNotEmpty()) parsed.tags.joinToString(",") else base.genres,
                    status = mapStatus(parsed.status),
                    inLibrary = parsed.inLibrary || base.inLibrary,
                    dateAdded = if (base.inLibrary && base.dateAdded > 0) base.dateAdded
                    else if (parsed.addedAt > 0) parsed.addedAt else System.currentTimeMillis(),
                    contentType = ContentType.NOVEL.name,
                )
            }
            if (novelId != null) {
                novels++
                chapters += entities.size
                if (!parsed.inLibrary) notInLibrary++
            }
        }

        return ImportResult(novels, chapters, categoryCount, notInLibrary, skipped)
    }

    /** Writes one entry, merging into an existing row; returns its id. */
    private suspend fun persist(
        sourceId: Long,
        url: String,
        chapters: List<ChapterEntity>,
        categoryIds: List<Long>,
        lastReadUrl: String?,
        lastReadAt: Long,
        historyByNovel: Map<Long, HistoryRow>,
        build: (NovelEntity?) -> NovelEntity,
    ): Long? {
        val existing = store.findNovel(sourceId, url)
        val novelId = store.saveNovel(build(existing))
        if (novelId <= 0L) return null

        val stored = store.chaptersOf(novelId).associateBy { it.url }
        val fresh = ArrayList<ChapterEntity>()
        val seen = HashSet<String>()
        for (c in chapters) {
            if (!seen.add(c.url)) continue
            val incoming = c.copy(novelId = novelId)
            val have = stored[c.url]
            if (have == null) fresh.add(incoming) else mergeChapter(have, incoming)?.let { store.updateChapter(it) }
        }
        if (fresh.isNotEmpty()) store.insertChapters(fresh)
        categoryIds.forEach { store.assignCategory(novelId, it) }

        if (lastReadUrl != null) {
            val chapter = store.chaptersOf(novelId).firstOrNull { it.url == lastReadUrl }
            val prior = historyByNovel[novelId]
            if (chapter != null && (prior == null || prior.readAt < lastReadAt)) {
                store.setLastRead(novelId, chapter.id)
                if (lastReadAt > 0) store.saveHistory(novelId, chapter.id, lastReadAt)
            }
        }
        return novelId
    }

    // --- row models ---

    private class ManatanChapter(
        val url: String,
        val name: String,
        val number: Float,
        val sourceOrder: Long,
        val dateUploadMs: Long,
        val fetchedSec: Long,
        val read: Boolean,
        val bookmark: Boolean,
        val lastPageRead: Long,
        val lastReadSec: Long,
    )

    private fun toChapter(row: Map<String, Any?>): ManatanChapter? {
        val url = row.str("url") ?: return null
        return ManatanChapter(
            url = url,
            name = row.str("name") ?: url,
            number = (row.double("chapter_number") ?: -1.0).toFloat(),
            sourceOrder = row.long("source_order") ?: 0L,
            dateUploadMs = row.long("upload_date") ?: 0L,
            fetchedSec = row.long("fetched_at") ?: 0L,
            read = row.truthy("is_read"),
            bookmark = row.truthy("is_bookmarked"),
            lastPageRead = row.long("last_page_read") ?: 0L,
            lastReadSec = row.long("last_read_at") ?: 0L,
        )
    }

    private class NovelToc(val index: Int, val key: String, val label: String, val dateUploaded: Long)

    private class ParsedNovel(
        val entryId: String,
        val sourceId: Long,
        val url: String,
        val title: String,
        val author: String?,
        val description: String?,
        val coverUrl: String?,
        val tags: List<String>,
        val status: String?,
        val inLibrary: Boolean,
        val addedAt: Long,
        val categoryIds: List<String>,
        val toc: List<NovelToc>,
    )

    private fun parseNovel(entryId: String, obj: JsonObject): ParsedNovel? {
        val details = obj["extensionDetails"] as? JsonObject
        val originItem = (obj["origin"] as? JsonObject)?.get("item") as? JsonObject
        val title = (obj.string("title") ?: details?.string("title"))?.takeIf { it.isNotBlank() } ?: return null
        // `ext-<sourceId>-<md5>`; the origin block spells its keys two ways.
        val origin = obj["origin"] as? JsonObject
        val sourceId = entryId.removePrefix("ext-").substringBefore('-').toLongOrNull()
            ?: (origin?.string("sourceId") ?: origin?.string("source_id"))?.toLongOrNull()
            ?: 0L
        val url = details?.string("url")?.takeIf { it.isNotBlank() }
            ?: originItem?.string("url")?.takeIf { it.isNotBlank() }
            ?: entryId
        val toc = (obj["toc"] as? JsonArray).orEmpty().mapIndexedNotNull { i, el ->
            val t = el as? JsonObject ?: return@mapIndexedNotNull null
            val key = t.string("href")?.removePrefix(TOC_PREFIX)?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
            NovelToc(
                index = t.int("chapterIndex") ?: i,
                key = key,
                label = t.string("label") ?: "Chapter ${i + 1}",
                dateUploaded = t.long("dateUploaded") ?: 0L,
            )
        }
        val cover = listOf(obj.string("cover"), details?.string("cover"), originItem?.string("cover"))
            .firstOrNull { it != null && (it.startsWith("http://") || it.startsWith("https://")) }
        return ParsedNovel(
            entryId = entryId,
            sourceId = sourceId,
            url = url,
            title = title,
            author = obj.string("author")?.takeIf { it.isNotBlank() }
                ?: (details?.get("authors") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.joinToString(", ")?.takeIf { it.isNotBlank() },
            description = details?.string("description")?.takeIf { it.isNotBlank() },
            coverUrl = cover,
            tags = (details?.get("tags") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            status = details?.string("status"),
            inLibrary = obj.bool("inLibrary") ?: true,
            addedAt = obj.long("addedAt") ?: 0L,
            categoryIds = obj.keySet("categoryIds").toList(),
            toc = toc,
        )
    }

    // --- helpers ---

    private fun parseGenres(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        return if (parsed is JsonArray) {
            parsed.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotEmpty() }
        } else {
            raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    /** Handles both Manatan's upper-case enum names and the novel API's lower-case strings. */
    private fun mapStatus(raw: String?): String = when (raw?.trim()?.lowercase()?.replace('-', '_')) {
        "ongoing" -> "ONGOING"
        "completed", "publishing_finished", "finished" -> "COMPLETED"
        "cancelled", "canceled" -> "CANCELLED"
        "on_hiatus", "hiatus" -> "HIATUS"
        else -> "UNKNOWN"
    }

    private fun String.asObject(): JsonObject? =
        runCatching { json.parseToJsonElement(this) as? JsonObject }.getOrNull()

    private fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }
    private fun JsonObject.string(key: String) = prim(key)?.contentOrNull
    private fun JsonObject.int(key: String) = prim(key)?.intOrNull
    private fun JsonObject.long(key: String) = prim(key)?.longOrNull
    private fun JsonObject.bool(key: String) = prim(key)?.booleanOrNull
    private fun JsonObject.keySet(key: String): Set<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.removePrefix(KEY_PREFIX) }?.toSet().orEmpty()

    private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList().orEmpty()

    private fun Map<String, Any?>.str(key: String): String? = this[key] as? String
    private fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) {
        is Long -> v
        is Double -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }
    private fun Map<String, Any?>.double(key: String): Double? = when (val v = this[key]) {
        is Double -> v
        is Long -> v.toDouble()
        else -> null
    }
    private fun Map<String, Any?>.truthy(key: String): Boolean = when (val v = this[key]) {
        is Long -> v != 0L
        is Boolean -> v
        else -> false
    }

    private companion object {
        const val TOC_PREFIX = "manatan-extension-chapter:"
        const val KEY_PREFIX = "key:"
    }
}
