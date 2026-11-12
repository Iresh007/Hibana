package com.opennovel.reader.backup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs against the maintainer's private backups. Skipped unless the
 * `HIBANA_FIXTURES` environment variable points at a directory containing
 * `mihon.tachibk` and/or `manatan.manatanbk`. Never commit those files.
 */
class RealFixturesTest {

    private fun fixture(name: String): File {
        val dir = System.getenv("HIBANA_FIXTURES")
        assumeTrue("HIBANA_FIXTURES not set", !dir.isNullOrBlank())
        val file = File(dir, name)
        assumeTrue("$name not present", file.isFile)
        return file
    }

    @Test
    fun `real mihon backup decodes and round trips`() {
        val file = fixture("mihon.tachibk")
        val backup = file.inputStream().use { TachibkCodec.read(it) }
        assertTrue(backup.backupManga.isNotEmpty())
        assertTrue(backup.backupCategories.all { it.name.isNotBlank() })
        assertEquals(backup, TachibkCodec.decode(TachibkCodec.encode(backup)))

        val store = FakeBackupStore()
        val result = runBlocking { BackupManager(store).importBackup(backup) }
        assertEquals(backup.backupManga.size, result.novels)
        assertEquals(backup.backupManga.count { it.favorite }, store.novels.count { it.inLibrary })
        println("mihon: ${result.novels} entries, ${result.chapters} chapters, ${result.categories} categories, " +
            "${result.notInLibrary} not in library, ${store.historyRows.size} history rows")
    }

    @Test
    fun `real manatan backup imports`() {
        val file = fixture("manatan.manatanbk")
        val store = FakeBackupStore()
        val result = runBlocking {
            ManatanBackupImporter(store).importFrom({ file.inputStream() })
        }
        assertTrue(result.novels > 0)
        println("manatan: ${result.novels} entries, ${result.chapters} chapters, ${result.categories} categories, " +
            "${result.notInLibrary} not in library, ${result.skipped} skipped")
    }
}
