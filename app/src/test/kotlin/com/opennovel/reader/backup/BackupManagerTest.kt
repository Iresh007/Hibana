package com.opennovel.reader.backup

import com.opennovel.reader.data.db.CategoryEntity
import com.opennovel.reader.data.db.ChapterEntity
import com.opennovel.reader.data.db.NovelCategoryCrossRef
import com.opennovel.reader.data.db.NovelEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManagerTest {

    private fun manga(
        url: String,
        favorite: Boolean = true,
        categories: List<Long> = emptyList(),
        chapters: List<BackupChapter> = emptyList(),
        history: List<BackupHistory> = emptyList(),
    ) = BackupManga(
        source = 42L, url = url, title = "T$url", author = "A", artist = "Art", description = "D",
        genre = listOf("x", "y"), status = 2, thumbnailUrl = "https://c/$url", dateAdded = 1_700_000_000_000L,
        chapters = chapters, categories = categories, favorite = favorite, history = history,
    )

    private fun chapter(url: String, order: Long, read: Boolean = false, bookmark: Boolean = false) =
        BackupChapter(url = url, name = "n$url", scanlator = "grp", read = read, bookmark = bookmark,
            lastPageRead = 3, dateFetch = 111, dateUpload = 222, chapterNumber = 2.5f, sourceOrder = order)

    @Test
    fun `import honours favorite flag and keeps non-favorites out of the library`() = runBlocking {
        val store = FakeBackupStore()
        val result = BackupManager(store).importBackup(
            Backup(backupManga = listOf(manga("/a"), manga("/b", favorite = false))),
        )
        assertTrue(store.novel("/a").inLibrary)
        assertFalse(store.novel("/b").inLibrary)
        assertEquals(2, result.novels)
        assertEquals(1, result.notInLibrary)
    }

    @Test
    fun `import carries read bookmark and fetch dates`() = runBlocking {
        val store = FakeBackupStore()
        BackupManager(store).importBackup(
            Backup(backupManga = listOf(manga("/a", chapters = listOf(chapter("/c1", 1, read = true), chapter("/c2", 0, bookmark = true))))),
        )
        val chs = store.chaptersFor(store.novel("/a").id)
        assertEquals(listOf("/c2", "/c1"), chs.map { it.url })
        val c1 = chs.first { it.url == "/c1" }
        assertTrue(c1.read); assertEquals(1f, c1.lastReadOffset, 0f); assertEquals(111L, c1.dateFetch)
        assertTrue(chs.first { it.url == "/c2" }.bookmark)
        assertEquals(2.5f, c1.number, 0f)
    }

    @Test
    fun `category membership is resolved by order not id`() = runBlocking {
        val store = FakeBackupStore()
        BackupManager(store).importBackup(
            Backup(
                backupManga = listOf(manga("/a", categories = listOf(1L))),
                backupCategories = listOf(
                    BackupCategory("First", order = 0, id = 5),
                    BackupCategory("Second", order = 1, id = 2),
                ),
            ),
        )
        val second = store.cats.first { it.name == "Second" }.id
        assertEquals(listOf(second), store.refs.filter { it.novelId == store.novel("/a").id }.map { it.categoryId })
    }

    @Test
    fun `history restores latest chapter and last read`() = runBlocking {
        val store = FakeBackupStore()
        BackupManager(store).importBackup(
            Backup(backupManga = listOf(manga("/a",
                chapters = listOf(chapter("/c1", 1), chapter("/c2", 0)),
                history = listOf(BackupHistory("/c1", 100), BackupHistory("/c2", 900))))),
        )
        val n = store.novel("/a")
        val c2 = store.chaptersFor(n.id).first { it.url == "/c2" }
        assertEquals(c2.id, store.historyRows.getValue(n.id).chapterId)
        assertEquals(900L, store.historyRows.getValue(n.id).readAt)
        assertEquals(c2.id, store.lastRead[n.id])
    }

    @Test
    fun `re-import merges instead of duplicating or un-reading`() = runBlocking {
        val store = FakeBackupStore()
        val mgr = BackupManager(store)
        mgr.importBackup(Backup(backupManga = listOf(manga("/a", chapters = listOf(chapter("/c1", 0, read = true))))))
        mgr.importBackup(Backup(backupManga = listOf(manga("/a", chapters = listOf(chapter("/c1", 0, read = false, bookmark = true), chapter("/c2", 1))))))
        assertEquals(1, store.novels.size)
        val chs = store.chaptersFor(store.novel("/a").id)
        assertEquals(2, chs.size)
        assertTrue(chs.first { it.url == "/c1" }.read)
        assertTrue(chs.first { it.url == "/c1" }.bookmark)
    }

    @Test
    fun `export writes bookmark dates unique category orders and history`() = runBlocking {
        val store = FakeBackupStore()
        store.sourceNames[42L] = "Src"
        store.cats.add(CategoryEntity(id = 10, name = "A", order = 0))
        store.cats.add(CategoryEntity(id = 11, name = "B", order = 0)) // colliding orders
        store.novels.add(NovelEntity(id = 1, sourceId = 42, url = "/a", title = "T", inLibrary = true, dateAdded = 5))
        store.novels.add(NovelEntity(id = 2, sourceId = 42, url = "/cache", title = "cache", inLibrary = false))
        store.chapters.add(ChapterEntity(id = 100, novelId = 1, url = "/c1", name = "c", read = true, bookmark = true, dateFetch = 7, dateUpload = 8, number = 1f))
        store.refs.add(NovelCategoryCrossRef(1, 11))
        store.historyRows[1] = HistoryRow(1, 100, 555)
        val backup = BackupManager(store).buildBackup()

        assertEquals(1, backup.backupManga.size)
        assertEquals(listOf(0L, 1L), backup.backupCategories.map { it.order })
        val m = backup.backupManga.single()
        assertEquals(listOf(backup.backupCategories.first { it.name == "B" }.order), m.categories)
        val c = m.chapters.single()
        assertTrue(c.bookmark); assertTrue(c.read); assertEquals(7L, c.dateFetch)
        assertEquals(BackupHistory("/c1", 555), m.history.single())
        assertEquals("Src", backup.backupSources.single().name)
    }

    @Test
    fun `hibana export imports back into an empty store`() = runBlocking {
        val src = FakeBackupStore()
        src.cats.add(CategoryEntity(id = 10, name = "A", order = 2))
        src.novels.add(NovelEntity(id = 1, sourceId = 42, url = "/a", title = "T", author = "au", genres = "g1,g2", status = "ONGOING", inLibrary = true, dateAdded = 5))
        src.chapters.add(ChapterEntity(id = 100, novelId = 1, url = "/c1", name = "c", bookmark = true, number = 3f, sourceOrder = 4))
        src.refs.add(NovelCategoryCrossRef(1, 10))
        val bytes = TachibkCodec.encode(BackupManager(src).buildBackup())

        val dst = FakeBackupStore()
        val res = BackupManager(dst).importBackup(TachibkCodec.decode(bytes))
        assertEquals(1, res.novels)
        val n = dst.novel("/a")
        assertEquals("g1,g2", n.genres); assertEquals("ONGOING", n.status); assertEquals("au", n.author)
        assertNotNull(dst.cats.firstOrNull { it.name == "A" })
        assertTrue(dst.chaptersFor(n.id).single().bookmark)
        assertEquals(1, dst.refs.size)
    }
}
