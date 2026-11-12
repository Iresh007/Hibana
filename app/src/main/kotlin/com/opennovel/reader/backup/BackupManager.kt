package com.opennovel.reader.backup

import com.opennovel.reader.data.db.CategoryDao
import com.opennovel.reader.data.db.ChapterDao
import com.opennovel.reader.data.db.ChapterEntity
import com.opennovel.reader.data.db.HistoryDao
import com.opennovel.reader.data.db.NovelDao
import com.opennovel.reader.data.db.NovelEntity
import com.opennovel.reader.source.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Summary shown to the user after a restore.
 *
 * [novels] counts every entry written; [notInLibrary] is the part of that which
 * was stored without joining the library (read-only history entries). [skipped]
 * counts entries in the file that were deliberately not imported.
 */
data class ImportResult(
    val novels: Int,
    val chapters: Int,
    val categories: Int,
    val notInLibrary: Int = 0,
    val skipped: Int = 0,
)

/**
 * Reads and writes Tachiyomi/Mihon `.tachibk` backups. Hibana's own export uses
 * the identical format, so a Hibana backup restores into Mihon and a Mihon backup
 * restores into Hibana. Entries are matched by (source, url) and merged, not
 * duplicated.
 *
 * Not representable in Hibana's database, so dropped on import (and never
 * written on export): tracker bindings, per-entry chapter-list flags and viewer
 * flags, excluded scanlators, notes, app/source preferences, extension stores,
 * all history rows except the most recent per entry, reading duration, comic
 * page position (Hibana stores a 0..1 scroll offset, not a page index) and the
 * artist (folded into author only when there is no author).
 */
class BackupManager internal constructor(private val store: BackupStore) {

    constructor(
        novelDao: NovelDao,
        chapterDao: ChapterDao,
        categoryDao: CategoryDao,
        sourceManager: SourceManager,
        historyDao: HistoryDao? = null,
    ) : this(RoomBackupStore(novelDao, chapterDao, categoryDao, historyDao, sourceManager))

    suspend fun export(out: OutputStream): Unit = withContext(Dispatchers.IO) {
        TachibkCodec.write(buildBackup(), out)
    }

    suspend fun import(input: InputStream): ImportResult = withContext(Dispatchers.IO) {
        importBackup(TachibkCodec.read(input))
    }

    internal suspend fun buildBackup(): Backup {
        val novels = store.libraryNovels()
        // Mihon keys category membership by `order`, so orders must be unique.
        val categories = store.categories().sortedWith(compareBy({ it.order }, { it.id }))
        val orderById = categories.withIndex().associate { (index, c) -> c.id to index.toLong() }
        val assignments = store.assignments().groupBy { it.novelId }
        val historyByNovel = store.history().associateBy { it.novelId }

        val manga = novels.map { novel ->
            val chapters = store.chaptersOf(novel.id)
            val chapterUrlById = chapters.associate { it.id to it.url }
            BackupManga(
                source = novel.sourceId,
                url = novel.url,
                title = novel.title,
                author = novel.author,
                description = novel.description,
                genre = novel.genres.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                status = statusToInt(novel.status),
                thumbnailUrl = novel.coverUrl,
                dateAdded = novel.dateAdded,
                chapters = chapters.map { it.toBackup() },
                categories = assignments[novel.id].orEmpty().mapNotNull { orderById[it.categoryId] },
                favorite = true,
                history = historyByNovel[novel.id]
                    ?.let { h -> chapterUrlById[h.chapterId]?.let { BackupHistory(it, h.readAt) } }
                    ?.let { listOf(it) }
                    .orEmpty(),
            )
        }
        return Backup(
            backupManga = manga,
            backupCategories = categories.map {
                BackupCategory(name = it.name, order = orderById.getValue(it.id), id = it.id)
            },
            backupSources = novels.map { it.sourceId }.distinct().map {
                BackupSource(name = store.sourceName(it).orEmpty(), sourceId = it)
            },
        )
    }

    internal suspend fun importBackup(backup: Backup): ImportResult {
        // Mihon's per-entry category list holds category *orders*.
        val orderToLocalId = HashMap<Long, Long>()
        backup.backupCategories.forEach { bc ->
            if (bc.name.isBlank()) return@forEach
            orderToLocalId[bc.order] = store.ensureCategory(bc.name, bc.order.toInt())
        }
        val historyByNovel = store.history().associateBy { it.novelId }

        var novelCount = 0
        var chapterCount = 0
        var notInLibrary = 0
        val now = System.currentTimeMillis()

        for (bm in backup.backupManga) {
            val existing = store.findNovel(bm.source, bm.url)
            val inLibrary = bm.favorite || existing?.inLibrary == true
            val dateAdded = when {
                existing != null && existing.inLibrary && existing.dateAdded > 0 -> existing.dateAdded
                bm.dateAdded > 0 -> bm.dateAdded
                bm.favoriteModifiedAt != null && bm.favoriteModifiedAt > 0 -> bm.favoriteModifiedAt * 1000
                inLibrary -> now
                else -> 0L
            }
            val base = existing ?: NovelEntity(sourceId = bm.source, url = bm.url, title = bm.title)
            val entity = base.copy(
                title = bm.title.ifBlank { base.title.ifBlank { bm.url } },
                author = bm.author?.takeIf { it.isNotBlank() }
                    ?: bm.artist?.takeIf { it.isNotBlank() }
                    ?: base.author,
                description = bm.description?.takeIf { it.isNotBlank() } ?: base.description,
                coverUrl = bm.thumbnailUrl?.takeIf { it.isNotBlank() } ?: base.coverUrl,
                genres = if (bm.genre.isNotEmpty()) bm.genre.joinToString(",") else base.genres,
                status = intToStatus(bm.status),
                inLibrary = inLibrary,
                dateAdded = dateAdded,
            )
            val novelId = store.saveNovel(entity)
            novelCount++
            if (!inLibrary) notInLibrary++

            val stored = store.chaptersOf(novelId).associateBy { it.url }
            val toInsert = ArrayList<ChapterEntity>()
            val seen = HashSet<String>()
            for (bc in bm.chapters) {
                if (!seen.add(bc.url)) continue
                val incoming = ChapterEntity(
                    novelId = novelId,
                    url = bc.url,
                    name = bc.name,
                    number = bc.chapterNumber,
                    dateUpload = bc.dateUpload,
                    dateFetch = bc.dateFetch,
                    read = bc.read,
                    bookmark = bc.bookmark,
                    lastReadOffset = if (bc.read) 1f else 0f,
                    sourceOrder = bc.sourceOrder.toInt(),
                )
                val have = stored[bc.url]
                if (have == null) {
                    toInsert.add(incoming)
                } else {
                    mergeChapter(have, incoming)?.let { store.updateChapter(it) }
                }
            }
            if (toInsert.isNotEmpty()) store.insertChapters(toInsert)
            chapterCount += bm.chapters.size

            bm.categories.forEach { order ->
                orderToLocalId[order]?.let { store.assignCategory(novelId, it) }
            }

            val latest = bm.history.maxByOrNull { it.lastRead }
            if (latest != null && latest.lastRead > 0) {
                val chapter = store.chaptersOf(novelId).firstOrNull { it.url == latest.url }
                val prior = historyByNovel[novelId]
                if (chapter != null && (prior == null || prior.readAt < latest.lastRead)) {
                    store.saveHistory(novelId, chapter.id, latest.lastRead)
                    store.setLastRead(novelId, chapter.id)
                }
            }
        }

        return ImportResult(novelCount, chapterCount, backup.backupCategories.size, notInLibrary)
    }

    private fun ChapterEntity.toBackup() = BackupChapter(
        url = url,
        name = name,
        read = read,
        bookmark = bookmark,
        lastPageRead = 0,
        dateFetch = dateFetch,
        dateUpload = dateUpload,
        chapterNumber = number,
        sourceOrder = sourceOrder.toLong(),
    )

    // Tachiyomi status ints: 0 unknown, 1 ongoing, 2 completed, 3 licensed,
    // 4 publishing finished, 5 cancelled, 6 on hiatus.
    private fun statusToInt(status: String): Int = when (status) {
        "ONGOING" -> 1
        "COMPLETED" -> 2
        "CANCELLED" -> 5
        "HIATUS" -> 6
        else -> 0
    }

    private fun intToStatus(value: Int): String = when (value) {
        1 -> "ONGOING"
        2, 4 -> "COMPLETED"
        5 -> "CANCELLED"
        6 -> "HIATUS"
        else -> "UNKNOWN"
    }
}
