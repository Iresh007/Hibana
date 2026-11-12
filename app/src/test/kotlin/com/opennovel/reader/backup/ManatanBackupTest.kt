package com.opennovel.reader.backup

import com.opennovel.reader.data.db.ContentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** Builds synthetic `.manatanbk` bytes following the layout in [ManatanContainer]. */
internal object ManatanFixtures {
    private fun zig(n: Long) = (n shl 1) xor (n shr 63)

    fun vNull() = ProtoWriter().apply { varint(1, 1) }.toByteArray()
    fun vInt(n: Long) = ProtoWriter().apply { varint(3, zig(n)) }.toByteArray()
    fun vDouble(d: Double) = ProtoWriter().apply { double(5, d) }.toByteArray()
    fun vStr(s: String) = ProtoWriter().apply { string(6, s) }.toByteArray()
    fun vMap(vararg cols: Pair<String, ByteArray>) = ProtoWriter().apply {
        message(9) { cols.forEach { (k, v) -> message(1) { string(1, k); bytes(2, v) } } }
    }.toByteArray()
    fun vList(rows: List<ByteArray>) = ProtoWriter().apply {
        message(8) { rows.forEach { bytes(1, it) } }
    }.toByteArray()

    fun mangaRow(
        id: Long, sourceId: String, url: String, title: String, inLibrary: Boolean,
        genre: String = "[\"Action\",\"Comedy\"]", status: String = "ONGOING",
    ) = vMap(
        "id" to vInt(id), "source_id" to vStr(sourceId), "url" to vStr(url), "title" to vStr(title),
        "title_override" to vNull(), "author" to vStr("Auth"), "artist" to vStr("Art"),
        "description" to vStr("Desc"), "genre" to vStr(genre), "status" to vStr(status),
        "thumbnail_url" to vStr("https://img/$id.png"), "in_library" to vInt(if (inLibrary) 1 else 0),
        "in_library_at" to if (inLibrary) vInt(1_700_000_000) else vNull(),
    )

    fun chapterRow(
        id: Long, mangaId: Long, url: String, number: Double, order: Long, read: Boolean = false,
        bookmark: Boolean = false, lastPage: Long = 0, lastReadAt: Long = 0,
    ) = vMap(
        "id" to vInt(id), "manga_id" to vInt(mangaId), "url" to vStr(url), "name" to vStr("Chapter $number"),
        "chapter_number" to vDouble(number), "source_order" to vInt(order),
        "upload_date" to vInt(1_745_074_310_000), "fetched_at" to vInt(1_745_339_256),
        "is_read" to vInt(if (read) 1 else 0), "is_bookmarked" to vInt(if (bookmark) 1 else 0),
        "last_page_read" to vInt(lastPage), "last_read_at" to vInt(lastReadAt), "scanlator" to vNull(),
    )

    fun table(name: String, rows: List<ByteArray>) = name to vList(rows)

    fun container(kv: List<Pair<String, String>> = emptyList(), tables: List<Pair<String, ByteArray>> = emptyList()): ByteArray {
        val w = ProtoWriter()
        w.varint(1, 10)
        w.string(2, "manual-backup")
        w.varint(3, 1_794_427_858_418L)
        kv.forEach { (k, v) -> w.message(5) { string(1, k); string(2, v) } }
        w.message(7) { message(9) { tables.forEach { (n, blob) -> message(1) { string(1, n); bytes(2, blob) } } } }
        return w.toByteArray()
    }

    fun gzip(bytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(bytes) }
        return bos.toByteArray()
    }

    const val NOVEL_ID = "ext-7881702694694453323-92c5e3057c5aab0c0b7b072455a98178"
    const val NOVEL_JSON = """{"addedAt":1791705548997,"author":"Au","categoryIds":["novelcat_1"],"cover":"/api/v1/extension/artwork/abc",
      "extensionDetails":{"authors":["Au"],"cover":"https://img/c.jpg","description":"Novel desc","status":"ongoing","tags":["Fantasy","Action"],"title":"Shadow","url":"https://site/novel/shadow"},
      "id":"$NOVEL_ID","inLibrary":true,"title":"Shadow",
      "origin":{"item_key":"k","kind":"extension","source_id":"7881702694694453323"},
      "readChapterKeys":["key:https://site/novel/shadow/chapter/1/"],"bookmarkedChapterKeys":["key:https://site/novel/shadow/chapter/2/"],
      "toc":[{"chapterIndex":0,"dateUploaded":1000,"href":"manatan-extension-chapter:https://site/novel/shadow/chapter/1/","label":"Chapter 1"},
             {"chapterIndex":1,"href":"manatan-extension-chapter:https://site/novel/shadow/chapter/2/","label":"Chapter 2"},
             {"chapterIndex":2,"href":"manatan-extension-chapter:https://site/novel/shadow/chapter/3/","label":"Chapter 3"}]}"""
    const val PROGRESS_JSON = """{"lastRead":1794402830568,"lastModified":1794402830568,"progress":0.5,
      "position":{"chapterIndex":2,"chapterKey":"https://site/novel/shadow/chapter/3/","chapterCharOffset":10}}"""
    const val CATEGORY_JSON = """{"id":"novelcat_1","name":"LNovels","order":3,"createdAt":1,"lastModified":2}"""
}

class ManatanBackupTest {
    private val f = ManatanFixtures

    private fun import(store: FakeBackupStore, bytes: ByteArray, includeNonLib: Boolean = true) = runBlocking {
        ManatanBackupImporter(store).importFrom({ ByteArrayInputStream(bytes) }, includeNonLib)
    }

    private fun mangaFile() = f.container(
        tables = listOf(
            f.table("anime", listOf(f.vMap("title" to f.vStr("ignored anime")))),
            f.table("chapters", listOf(
                f.chapterRow(1, 1, "/m1/c1", 1.0, 1, read = true, lastReadAt = 1_785_000_000),
                f.chapterRow(2, 1, "/m1/c2", 2.0, 2, bookmark = true),
                f.chapterRow(3, 1, "/m1/c3", 3.0, 3),
                f.chapterRow(4, 2, "/m2/c1", 1.0, 1, read = true),
                f.chapterRow(5, 2, "/m2/c2", 2.0, 2),
                f.chapterRow(6, 3, "/m3/c1", 1.0, 1),
                f.chapterRow(7, 99, "/orphan/c1", 1.0, 1, read = true),
            )),
            f.table("manga", listOf(
                f.mangaRow(1, "6247824327199706550", "/m1", "Library Manga", inLibrary = true),
                f.mangaRow(2, "6247824327199706550", "/m2", "Read But Removed", inLibrary = false, status = "ON_HIATUS"),
                f.mangaRow(3, "6247824327199706550", "/m3", "Pure Cache", inLibrary = false),
            )),
            f.table("categories", emptyList()),
            f.table("mangaCategories", emptyList()),
        ),
    )

    @Test
    fun `imports manga library chapters and marks them comic`() {
        val store = FakeBackupStore()
        val res = import(store, f.gzip(mangaFile()))
        val m = store.novel("/m1")
        assertEquals(6247824327199706550L, m.sourceId)
        assertEquals("Library Manga", m.title)
        assertTrue(m.inLibrary)
        assertEquals(ContentType.COMIC.name, m.contentType)
        assertEquals("Action,Comedy", m.genres)
        assertEquals("ONGOING", m.status)
        assertEquals("Auth", m.author)
        assertEquals(1_700_000_000_000L, m.dateAdded)
        assertEquals("https://img/1.png", m.coverUrl)

        val ch = store.chaptersFor(m.id)
        assertEquals(3, ch.size)
        // Manatan is oldest-first; Hibana's manga order is newest-first.
        assertEquals(listOf("/m1/c3", "/m1/c2", "/m1/c1"), ch.map { it.url })
        val c1 = ch.first { it.url == "/m1/c1" }
        assertTrue(c1.read); assertEquals(1f, c1.lastReadOffset, 0f)
        assertEquals(1_745_339_256_000L, c1.dateFetch)
        assertEquals(1_745_074_310_000L, c1.dateUpload)
        assertTrue(ch.first { it.url == "/m1/c2" }.bookmark)
        assertEquals(c1.id, store.historyRows.getValue(m.id).chapterId)
        assertEquals(1_785_000_000_000L, store.historyRows.getValue(m.id).readAt)

        assertEquals(2, res.novels)
        assertEquals(1, res.notInLibrary)
        assertEquals(1, res.skipped)
        assertEquals(5, res.chapters)
    }

    @Test
    fun `non-library manga with progress is kept out of the library`() {
        val store = FakeBackupStore()
        import(store, mangaFile())
        val removed = store.novel("/m2")
        assertFalse(removed.inLibrary)
        assertEquals("ON_HIATUS".let { "HIATUS" }, removed.status)
        assertTrue(store.novels.none { it.url == "/m3" })
        assertTrue(store.chapters.none { it.url == "/orphan/c1" })
    }

    @Test
    fun `can import the library only`() {
        val store = FakeBackupStore()
        val res = import(store, mangaFile(), includeNonLib = false)
        assertEquals(1, store.novels.size)
        assertEquals(1, res.novels)
        assertEquals(2, res.skipped)
    }

    @Test
    fun `imports novel records with real key shapes`() {
        val store = FakeBackupStore()
        val bytes = f.container(kv = listOf(
            "metadata:${f.NOVEL_ID}" to f.NOVEL_JSON,
            "progress:${f.NOVEL_ID}" to f.PROGRESS_JSON,
            "category:novelcat_1" to f.CATEGORY_JSON,
            "category_metadata:novelcat_1" to """{"sortBy":"latestChapterFetchAt","sortDesc":false}""",
        ))
        val res = import(store, bytes)
        val n = store.novel("https://site/novel/shadow")
        assertEquals(7881702694694453323L, n.sourceId)
        assertEquals(ContentType.NOVEL.name, n.contentType)
        assertEquals("Fantasy,Action", n.genres)
        assertEquals("https://img/c.jpg", n.coverUrl) // the server-relative artwork path is rejected
        assertEquals("Novel desc", n.description)
        assertEquals("ONGOING", n.status)
        assertEquals(1791705548997L, n.dateAdded)
        val ch = store.chaptersFor(n.id)
        assertEquals(listOf("https://site/novel/shadow/chapter/1/", "https://site/novel/shadow/chapter/2/", "https://site/novel/shadow/chapter/3/"), ch.map { it.url })
        assertTrue(ch[0].read); assertTrue(ch[1].bookmark); assertFalse(ch[2].read)
        assertEquals(1000L, ch[0].dateUpload)
        assertEquals(ch[2].id, store.lastRead[n.id])
        assertEquals(1794402830568L, store.historyRows.getValue(n.id).readAt)
        val cat = store.cats.single()
        assertEquals("LNovels", cat.name); assertEquals(3, cat.order)
        assertEquals(cat.id, store.refs.single().categoryId)
        assertEquals(1, res.novels); assertEquals(3, res.chapters); assertEquals(1, res.categories)
    }

    @Test
    fun `unknown tables and fields are skipped and truncation is reported`() {
        val bytes = f.container(tables = listOf(f.table("immersionArchive", listOf(f.vMap("x" to f.vInt(1))))))
        val store = FakeBackupStore()
        val res = import(store, bytes)
        assertEquals(0, res.novels)
        assertTrue(store.novels.isEmpty())
        try {
            import(store, mangaFile().copyOf(60))
            org.junit.Assert.fail("expected failure on truncated input")
        } catch (e: java.io.IOException) {
            // ProtoFormatException is an IOException
        }
    }

    @Test
    fun `value decoder handles zigzag negatives doubles nulls lists and maps`() {
        val v = ManatanContainer.decodeValue(ProtoReader(f.vMap(
            "neg" to f.vInt(-5), "big" to f.vInt(1745074310000L), "d" to f.vDouble(1.25), "n" to f.vNull(),
            "l" to f.vList(listOf(f.vInt(1), f.vStr("x"))),
        ))) as Map<*, *>
        assertEquals(-5L, v["neg"]); assertEquals(1745074310000L, v["big"]); assertEquals(1.25, v["d"])
        assertNull(v["n"]); assertEquals(listOf(1L, "x"), v["l"])
        assertEquals(Long.MIN_VALUE, zigzagDecode(-1L))
    }
}
