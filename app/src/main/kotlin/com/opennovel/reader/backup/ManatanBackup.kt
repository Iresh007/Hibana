package com.opennovel.reader.backup

import com.opennovel.reader.data.db.CategoryDao
import com.opennovel.reader.data.db.CategoryEntity
import com.opennovel.reader.data.db.ChapterDao
import com.opennovel.reader.data.db.ChapterEntity
import com.opennovel.reader.data.db.ContentType
import com.opennovel.reader.data.db.NovelCategoryCrossRef
import com.opennovel.reader.data.db.NovelDao
import com.opennovel.reader.data.db.NovelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Imports Manatan `.manatanbk` backups.
 *
 * The format is not Tachiyomi's, so it needs its own reader. A backup is gzip
 * over a small protobuf envelope whose repeated field 5 carries a flat
 * key/value store; every value is a JSON document. Keys are namespaced:
 *
 *  - `metadata:<entryId>` — the entry: title, author, cover, categoryIds, and a
 *    `toc` array of chapters. Read and bookmarked chapters are listed by key in
 *    `readChapterKeys` / `bookmarkedChapterKeys`.
 *  - `progress:<entryId>` — reading position, including the chapter key.
 *  - `category:<id>` — a shelf, with name and order.
 *  - `category_metadata:<id>` — that shelf's sort preference.
 *
 * The envelope is walked directly rather than through generated protobuf
 * classes: only two field numbers are needed, and a real schema would have to
 * be kept in step with an app whose format is not published.
 *
 * A real export also carries an image cache that dwarfs the library data — the
 * sample used to build this was 4.8 MB compressed and 68 MB inflated. Cached
 * artwork is deliberately skipped: covers are re-fetched from the source, and
 * decoding tens of megabytes of images to throw them away would make an import
 * appear to hang.
 */
class ManatanBackupImporter(
    private val novelDao: NovelDao,
    private val chapterDao: ChapterDao,
    private val categoryDao: CategoryDao,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun import(input: InputStream): ImportResult = withContext(Dispatchers.IO) {
        val bytes = GZIPInputStream(input).use { it.readBytes() }
        val store = readKeyValueStore(bytes)

        val categoryIdByExternalId = importCategories(store)
        importEntries(store, categoryIdByExternalId)
    }

    // --- protobuf envelope ---

    /**
     * Pulls the flat key/value store out of the envelope.
     *
     * Each repeated field-5 record holds the key in field 1 and the JSON value
     * in field 2. Unknown fields are skipped by wire type so a newer Manatan
     * build adding fields does not break the read.
     */
    private fun readKeyValueStore(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        forEachField(bytes, 0, bytes.size) { number, wireType, from, to ->
            if (number == 5 && wireType == WIRE_LENGTH) {
                var key: String? = null
                var value: String? = null
                forEachField(bytes, from, to) { inner, innerWire, iFrom, iTo ->
                    if (innerWire == WIRE_LENGTH) {
                        val text = String(bytes, iFrom, iTo - iFrom, Charsets.UTF_8)
                        when (inner) {
                            1 -> key = text
                            2 -> value = text
                        }
                    }
                }
                val k = key
                val v = value
                if (k != null && v != null) out[k] = v
            }
        }
        return out
    }

    /**
     * Walks protobuf records between [start] and [end], reporting each field's
     * number, wire type and payload bounds. Malformed input stops the walk
     * rather than throwing: a truncated backup should import what it can.
     */
    private inline fun forEachField(
        bytes: ByteArray,
        start: Int,
        end: Int,
        onField: (number: Int, wireType: Int, from: Int, to: Int) -> Unit,
    ) {
        var i = start
        while (i < end) {
            val (tag, afterTag) = readVarint(bytes, i, end) ?: return
            i = afterTag
            val number = (tag ushr 3).toInt()
            when (val wireType = (tag and 7L).toInt()) {
                WIRE_VARINT -> {
                    val (_, next) = readVarint(bytes, i, end) ?: return
                    onField(number, wireType, i, next)
                    i = next
                }

                WIRE_LENGTH -> {
                    val (len, afterLen) = readVarint(bytes, i, end) ?: return
                    val to = afterLen + len.toInt()
                    if (len < 0 || to > end || to < afterLen) return
                    onField(number, wireType, afterLen, to)
                    i = to
                }

                WIRE_FIXED64 -> { if (i + 8 > end) return; onField(number, wireType, i, i + 8); i += 8 }
                WIRE_FIXED32 -> { if (i + 4 > end) return; onField(number, wireType, i, i + 4); i += 4 }
                else -> return
            }
        }
    }

    private fun readVarint(bytes: ByteArray, start: Int, end: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = start
        while (i < end) {
            val b = bytes[i].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return result to i
            shift += 7
            if (shift > 63) return null
        }
        return null
    }

    // --- domain mapping ---

    private suspend fun importCategories(store: Map<String, String>): Map<String, Long> {
        val existingByName = categoryDao.getAll().associateBy { it.name }
        val mapped = LinkedHashMap<String, Long>()

        store.filterKeys { it.startsWith("category:") }.forEach { (key, raw) ->
            val obj = raw.asObject() ?: return@forEach
            val externalId = obj.string("id") ?: key.removePrefix("category:")
            val name = obj.string("name")?.takeIf { it.isNotBlank() } ?: return@forEach
            val order = obj.int("order") ?: 0

            // Merge onto a shelf of the same name rather than creating a
            // duplicate, so re-importing a backup is idempotent.
            val localId = existingByName[name]?.id
                ?: categoryDao.insert(CategoryEntity(name = name, order = order))
                    .takeIf { it != -1L }
                ?: categoryDao.getAll().firstOrNull { it.name == name }?.id
                ?: return@forEach

            mapped[externalId] = localId
        }
        return mapped
    }

    private suspend fun importEntries(
        store: Map<String, String>,
        categoryIds: Map<String, Long>,
    ): ImportResult {
        var novels = 0
        var chapters = 0

        store.filterKeys { it.startsWith("metadata:") }.forEach { (key, raw) ->
            val entryId = key.removePrefix("metadata:")
            val obj = raw.asObject() ?: return@forEach

            val title = obj.string("title")?.takeIf { it.isNotBlank() } ?: return@forEach
            val toc = (obj["toc"] as? JsonArray).orEmpty()

            // Manatan identifies an entry by its own opaque id, not by a source
            // url. The id is used as the url so re-importing updates the same
            // row; the entry still needs re-matching to a source before it can
            // fetch, which migration handles.
            val sourceId = entryId.substringAfter("ext-", "").substringBefore('-')
                .toLongOrNull() ?: 0L
            val url = obj.string("url") ?: entryId

            val existing = novelDao.findByUrl(sourceId, url)
            val entity = (existing ?: NovelEntity(sourceId = sourceId, url = url, title = title)).copy(
                title = title,
                author = obj.string("author") ?: existing?.author,
                description = (obj["extensionDetails"] as? JsonObject)?.string("description")
                    ?: existing?.description,
                coverUrl = obj.string("cover") ?: existing?.coverUrl,
                inLibrary = obj.bool("inLibrary") ?: true,
                dateAdded = obj.long("addedAt") ?: System.currentTimeMillis(),
                // Manatan is a novel reader, so anything it exported is prose.
                contentType = ContentType.NOVEL.name,
            )
            val novelId = if (existing != null) {
                novelDao.update(entity); entity.id
            } else {
                novelDao.upsert(entity)
            }
            novels++

            val readKeys = obj.stringSet("readChapterKeys")
            val bookmarkedKeys = obj.stringSet("bookmarkedChapterKeys")

            val rows = toc.mapIndexedNotNull { index, element ->
                val chapter = element as? JsonObject ?: return@mapIndexedNotNull null
                val chapterKey = chapter.string("key")
                    ?: chapter.string("url")
                    ?: return@mapIndexedNotNull null
                ChapterEntity(
                    novelId = novelId,
                    url = chapterKey,
                    name = chapter.string("title") ?: chapter.string("name") ?: "Chapter ${index + 1}",
                    number = chapter.double("number")?.toFloat() ?: (index + 1).toFloat(),
                    dateUpload = chapter.long("publishedAt") ?: chapter.long("releasedAt") ?: 0L,
                    // Imported chapters are back catalogue, not updates, so they
                    // are left unstamped and stay out of the Updates feed.
                    dateFetch = 0L,
                    read = chapterKey in readKeys,
                    bookmark = chapterKey in bookmarkedKeys,
                    lastReadOffset = if (chapterKey in readKeys) 1f else 0f,
                    sourceOrder = index,
                )
            }
            chapterDao.insertAll(rows)
            chapters += rows.size

            obj.stringSet("categoryIds").forEach { external ->
                categoryIds[external]?.let { categoryDao.assign(NovelCategoryCrossRef(novelId, it)) }
            }

            applyProgress(store["progress:$entryId"], novelId, rows)
        }

        return ImportResult(novels, chapters, categoryIds.size)
    }

    /**
     * Restores the resume point. Manatan records position by chapter key, which
     * survives re-import; the chapter index it also stores does not, because a
     * table of contents can gain entries between backups.
     */
    private suspend fun applyProgress(
        raw: String?,
        novelId: Long,
        rows: List<ChapterEntity>,
    ) {
        val obj = raw?.asObject() ?: return
        val position = obj["position"] as? JsonObject ?: return
        val chapterKey = position.string("chapterKey") ?: return
        if (rows.none { it.url == chapterKey }) return

        val saved = chapterDao.getForNovel(novelId).firstOrNull { it.url == chapterKey } ?: return
        val fraction = obj.double("progress")?.toFloat()?.coerceIn(0f, 1f) ?: 0f
        chapterDao.setReadState(saved.id, read = fraction >= 0.98f, offset = fraction)
        novelDao.setLastReadChapter(novelId, saved.id)
    }

    // --- json helpers ---

    private fun String.asObject(): JsonObject? =
        runCatching { json.parseToJsonElement(this).jsonObject }.getOrNull()

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull
    private fun JsonObject.double(key: String) = this[key]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.stringSet(key: String): Set<String> =
        (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> =
        this?.toList().orEmpty()

    private companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH = 2
        const val WIRE_FIXED32 = 5
    }
}
