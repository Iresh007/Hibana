package com.opennovel.reader.backup

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Reads and writes the Mihon `.tachibk` wire format (see [Backup]). */
object TachibkCodec {

    /**
     * Reads a backup, accepting gzip (what Mihon writes) or raw protobuf (which
     * Mihon's own decoder also accepts). Legacy JSON and zstd files get a clear
     * error instead of a confusing protobuf failure.
     */
    fun read(input: InputStream): Backup = decode(readAllMaybeGzip(input))

    /** Writes a gzipped backup, matching Mihon's output container. */
    fun write(backup: Backup, out: OutputStream) {
        val bytes = encode(backup)
        GZIPOutputStream(out).use { it.write(bytes) }
    }

    internal fun readAllMaybeGzip(input: InputStream): ByteArray {
        val stream = BufferedInputStream(input, 8192)
        stream.mark(4)
        val head = ByteArray(4)
        var n = 0
        while (n < 4) {
            val r = stream.read(head, n, 4 - n)
            if (r < 0) break
            n += r
        }
        stream.reset()
        if (n == 0) throw IOException("Backup file is empty")
        val b0 = head[0].toInt() and 0xFF
        val b1 = if (n > 1) head[1].toInt() and 0xFF else -1
        return when {
            b0 == 0x1F && b1 == 0x8B -> GZIPInputStream(stream).use { it.readBytes() }
            b0 == 0x7B -> throw IOException("This is a legacy JSON backup, which is not supported")
            n == 4 && b0 == 0x28 && b1 == 0xB5 && (head[2].toInt() and 0xFF) == 0x2F &&
                (head[3].toInt() and 0xFF) == 0xFD ->
                throw IOException("Zstandard-compressed backups are not supported")
            else -> stream.readBytes()
        }
    }

    // --- decode ---

    fun decode(bytes: ByteArray): Backup {
        val manga = ArrayList<BackupManga>()
        val categories = ArrayList<BackupCategory>()
        val sources = ArrayList<BackupSource>()
        val r = ProtoReader(bytes)
        while (r.next()) {
            when {
                r.field == 1 && r.wireType == WIRE_LEN -> manga.add(readManga(r.readMessage()))
                r.field == 2 && r.wireType == WIRE_LEN -> categories.add(readCategory(r.readMessage()))
                r.field == 101 && r.wireType == WIRE_LEN -> sources.add(readSource(r.readMessage()))
                else -> r.skip()
            }
        }
        return Backup(manga, categories, sources)
    }

    private fun readManga(r: ProtoReader): BackupManga {
        var source = 0L
        var url = ""
        var title = ""
        var artist: String? = null
        var author: String? = null
        var description: String? = null
        val genre = ArrayList<String>()
        var status = 0
        var thumbnailUrl: String? = null
        var dateAdded = 0L
        val chapters = ArrayList<BackupChapter>()
        val categories = ArrayList<Long>()
        val tracking = ArrayList<BackupTracking>()
        var favorite = true
        var chapterFlags = 0
        val history = ArrayList<BackupHistory>()
        var favoriteModifiedAt: Long? = null
        while (r.next()) {
            val len = r.wireType == WIRE_LEN
            when (r.field) {
                1 -> source = r.readVarint()
                2 -> if (len) url = r.readString() else r.skip()
                3 -> if (len) title = r.readString() else r.skip()
                4 -> if (len) artist = r.readString() else r.skip()
                5 -> if (len) author = r.readString() else r.skip()
                6 -> if (len) description = r.readString() else r.skip()
                7 -> if (len) genre.add(r.readString()) else r.skip()
                8 -> status = r.readInt()
                9 -> if (len) thumbnailUrl = r.readString() else r.skip()
                13 -> dateAdded = r.readVarint()
                16 -> if (len) chapters.add(readChapter(r.readMessage())) else r.skip()
                17 -> categories.let { r.readVarintsInto(it) }
                18 -> if (len) tracking.add(readTracking(r.readMessage())) else r.skip()
                100 -> favorite = r.readBool()
                101 -> chapterFlags = r.readInt()
                104 -> if (len) history.add(readHistory(r.readMessage())) else r.skip()
                107 -> favoriteModifiedAt = r.readVarint()
                else -> r.skip()
            }
        }
        return BackupManga(
            source, url, title, artist, author, description, genre, status, thumbnailUrl,
            dateAdded, chapters, categories, tracking, favorite, chapterFlags, history,
            favoriteModifiedAt,
        )
    }

    private fun readChapter(r: ProtoReader): BackupChapter {
        var url = ""
        var name = ""
        var scanlator: String? = null
        var read = false
        var bookmark = false
        var lastPageRead = 0L
        var dateFetch = 0L
        var dateUpload = 0L
        var number = 0f
        var sourceOrder = 0L
        while (r.next()) {
            val len = r.wireType == WIRE_LEN
            when (r.field) {
                1 -> if (len) url = r.readString() else r.skip()
                2 -> if (len) name = r.readString() else r.skip()
                3 -> if (len) scanlator = r.readString() else r.skip()
                4 -> read = r.readBool()
                5 -> bookmark = r.readBool()
                6 -> lastPageRead = r.readVarint()
                7 -> dateFetch = r.readVarint()
                8 -> dateUpload = r.readVarint()
                9 -> if (r.wireType == WIRE_FIXED32) number = r.readFloat() else r.skip()
                10 -> sourceOrder = r.readVarint()
                else -> r.skip()
            }
        }
        return BackupChapter(url, name, scanlator, read, bookmark, lastPageRead, dateFetch, dateUpload, number, sourceOrder)
    }

    private fun readCategory(r: ProtoReader): BackupCategory {
        var name = ""
        var order = 0L
        var id = 0L
        var flags = 0L
        while (r.next()) {
            when (r.field) {
                1 -> if (r.wireType == WIRE_LEN) name = r.readString() else r.skip()
                2 -> order = r.readVarint()
                3 -> id = r.readVarint()
                100 -> flags = r.readVarint()
                else -> r.skip()
            }
        }
        return BackupCategory(name, order, id, flags)
    }

    private fun readSource(r: ProtoReader): BackupSource {
        var name = ""
        var id = 0L
        while (r.next()) {
            when (r.field) {
                1 -> if (r.wireType == WIRE_LEN) name = r.readString() else r.skip()
                2 -> id = r.readVarint()
                else -> r.skip()
            }
        }
        return BackupSource(name, id)
    }

    private fun readHistory(r: ProtoReader): BackupHistory {
        var url = ""
        var lastRead = 0L
        var duration = 0L
        while (r.next()) {
            when (r.field) {
                1 -> if (r.wireType == WIRE_LEN) url = r.readString() else r.skip()
                2 -> lastRead = r.readVarint()
                3 -> duration = r.readVarint()
                else -> r.skip()
            }
        }
        return BackupHistory(url, lastRead, duration)
    }

    private fun readTracking(r: ProtoReader): BackupTracking {
        var syncId = 0
        var libraryId = 0L
        var mediaIdInt = 0
        var trackingUrl = ""
        var title = ""
        var lastChapterRead = 0f
        var totalChapters = 0
        var score = 0f
        var status = 0
        var started = 0L
        var finished = 0L
        var private = false
        var mediaId = 0L
        while (r.next()) {
            val len = r.wireType == WIRE_LEN
            val f32 = r.wireType == WIRE_FIXED32
            when (r.field) {
                1 -> syncId = r.readInt()
                2 -> libraryId = r.readVarint()
                3 -> mediaIdInt = r.readInt()
                4 -> if (len) trackingUrl = r.readString() else r.skip()
                5 -> if (len) title = r.readString() else r.skip()
                6 -> if (f32) lastChapterRead = r.readFloat() else r.skip()
                7 -> totalChapters = r.readInt()
                8 -> if (f32) score = r.readFloat() else r.skip()
                9 -> status = r.readInt()
                10 -> started = r.readVarint()
                11 -> finished = r.readVarint()
                12 -> private = r.readBool()
                100 -> mediaId = r.readVarint()
                else -> r.skip()
            }
        }
        return BackupTracking(
            syncId, libraryId, mediaIdInt, trackingUrl, title, lastChapterRead, totalChapters,
            score, status, started, finished, private, mediaId,
        )
    }

    // --- encode ---

    fun encode(backup: Backup): ByteArray {
        val w = ProtoWriter()
        backup.backupManga.forEach { m -> w.message(1) { writeManga(m) } }
        backup.backupCategories.forEach { c ->
            w.message(2) {
                string(1, c.name)
                if (c.order != 0L) varint(2, c.order)
                if (c.id != 0L) varint(3, c.id)
                if (c.flags != 0L) varint(100, c.flags)
            }
        }
        backup.backupSources.forEach { s ->
            w.message(101) {
                if (s.name.isNotEmpty()) string(1, s.name)
                varint(2, s.sourceId)
            }
        }
        return w.toByteArray()
    }

    private fun ProtoWriter.writeManga(m: BackupManga) {
        varint(1, m.source)
        string(2, m.url)
        if (m.title.isNotEmpty()) string(3, m.title)
        m.artist?.let { string(4, it) }
        m.author?.let { string(5, it) }
        m.description?.let { string(6, it) }
        m.genre.forEach { string(7, it) }
        if (m.status != 0) int(8, m.status)
        m.thumbnailUrl?.let { string(9, it) }
        if (m.dateAdded != 0L) varint(13, m.dateAdded)
        m.chapters.forEach { c -> message(16) { writeChapter(c) } }
        m.categories.forEach { varint(17, it) }
        m.tracking.forEach { t -> message(18) { writeTracking(t) } }
        // Mihon omits favorite=true; writing it explicitly is equally valid.
        bool(100, m.favorite)
        if (m.chapterFlags != 0) int(101, m.chapterFlags)
        m.history.forEach { h ->
            message(104) {
                string(1, h.url)
                varint(2, h.lastRead)
                if (h.readDuration != 0L) varint(3, h.readDuration)
            }
        }
        m.favoriteModifiedAt?.let { varint(107, it) }
    }

    private fun ProtoWriter.writeChapter(c: BackupChapter) {
        string(1, c.url)
        string(2, c.name)
        c.scanlator?.let { string(3, it) }
        if (c.read) bool(4, true)
        if (c.bookmark) bool(5, true)
        if (c.lastPageRead != 0L) varint(6, c.lastPageRead)
        if (c.dateFetch != 0L) varint(7, c.dateFetch)
        if (c.dateUpload != 0L) varint(8, c.dateUpload)
        if (c.chapterNumber != 0f) float(9, c.chapterNumber)
        if (c.sourceOrder != 0L) varint(10, c.sourceOrder)
    }

    private fun ProtoWriter.writeTracking(t: BackupTracking) {
        int(1, t.syncId)
        varint(2, t.libraryId)
        if (t.mediaIdInt != 0) int(3, t.mediaIdInt)
        if (t.trackingUrl.isNotEmpty()) string(4, t.trackingUrl)
        if (t.title.isNotEmpty()) string(5, t.title)
        if (t.lastChapterRead != 0f) float(6, t.lastChapterRead)
        if (t.totalChapters != 0) int(7, t.totalChapters)
        if (t.score != 0f) float(8, t.score)
        if (t.status != 0) int(9, t.status)
        if (t.startedReadingDate != 0L) varint(10, t.startedReadingDate)
        if (t.finishedReadingDate != 0L) varint(11, t.finishedReadingDate)
        if (t.private) bool(12, true)
        if (t.mediaId != 0L) varint(100, t.mediaId)
    }
}
