package com.opennovel.reader.backup

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

class TachibkCodecTest {

    private fun gzip(bytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(bytes) }
        return bos.toByteArray()
    }

    /** A backup written field by field with the Mihon v0.21.3 numbers, independent of the codec. */
    private fun handBuilt(): ByteArray {
        val w = ProtoWriter()
        w.message(1) {
            varint(1, 6247824327199706550L)
            string(2, "/series/overgeared")
            string(3, "Overgeared")
            string(4, "REDICE Studio")
            string(5, "LEE Dong Wook")
            string(6, "desc")
            string(7, "Manhwa")
            string(7, "Action")
            int(8, 1)
            string(9, "https://cdn.example/c.webp")
            varint(13, 1700000000000L)
            message(16) {
                string(1, "/series/overgeared/chapter/0")
                string(2, "Chapter 0 - Prologue")
                string(3, "Group A")
                bool(4, true)
                bool(5, true)
                varint(6, 12)
                varint(7, 1734720182874L)
                varint(8, 1746891431000L)
                float(9, 1.5f)
                varint(10, 347)
            }
            varint(17, 2) // unpacked repeated, as kotlinx.serialization writes it
            varint(17, 0)
            message(18) { int(1, 3); varint(2, 68483906L); float(6, 37f) }
            bool(100, false) // favorite = false; absent means true
            int(101, 12345)
            message(104) { string(1, "/series/overgeared/chapter/0"); varint(2, 1752034880889L); varint(3, 1037133L) }
            varint(105, 0) // update strategy enum: must be skipped
            string(112, "{\"slug\":\"x\"}") // memo: must be skipped
        }
        w.message(1) { varint(1, 5L); string(2, "/u"); string(3, "Default favorite") }
        w.message(2) { string(1, "no interest now"); varint(3, 1); varint(100, 64) }
        w.message(2) { string(1, "Reading"); varint(2, 4); varint(3, 9) }
        w.message(101) { string(1, "Asura Scans"); varint(2, 6247824327199706550L) }
        w.message(104) { string(1, "reader_tapping_inverted") } // preferences: skipped
        return w.toByteArray()
    }

    @Test
    fun `decodes every field using Mihon field numbers`() {
        val backup = TachibkCodec.decode(handBuilt())
        assertEquals(2, backup.backupManga.size)
        val m = backup.backupManga[0]
        assertEquals(6247824327199706550L, m.source)
        assertEquals("/series/overgeared", m.url)
        assertEquals("Overgeared", m.title)
        assertEquals("REDICE Studio", m.artist)
        assertEquals("LEE Dong Wook", m.author)
        assertEquals(listOf("Manhwa", "Action"), m.genre)
        assertEquals(1, m.status)
        assertEquals(1700000000000L, m.dateAdded)
        assertEquals(listOf(2L, 0L), m.categories)
        assertFalse(m.favorite)
        assertEquals(12345, m.chapterFlags)
        val c = m.chapters.single()
        assertEquals("Group A", c.scanlator)
        assertTrue(c.read); assertTrue(c.bookmark)
        assertEquals(12L, c.lastPageRead)
        assertEquals(1734720182874L, c.dateFetch)
        assertEquals(1746891431000L, c.dateUpload)
        assertEquals(1.5f, c.chapterNumber, 0f)
        assertEquals(347L, c.sourceOrder)
        assertEquals(1752034880889L, m.history.single().lastRead)
        assertEquals(1037133L, m.history.single().readDuration)
        assertEquals(37f, m.tracking.single().lastChapterRead, 0f)
        assertEquals(68483906L, m.tracking.single().libraryId)
        assertEquals("Asura Scans", backup.backupSources.single().name)
    }

    @Test
    fun `favorite defaults to true when absent`() {
        assertTrue(TachibkCodec.decode(handBuilt()).backupManga[1].favorite)
    }

    @Test
    fun `category order is field 2 and id is field 3`() {
        val cats = TachibkCodec.decode(handBuilt()).backupCategories
        assertEquals(BackupCategory("no interest now", order = 0, id = 1, flags = 64), cats[0])
        assertEquals(BackupCategory("Reading", order = 4, id = 9), cats[1])
    }

    @Test
    fun `encoded category uses Mihon numbering`() {
        val bytes = TachibkCodec.encode(Backup(backupCategories = listOf(BackupCategory("B", order = 1, id = 7, flags = 3))))
        val top = ProtoReader(bytes)
        assertTrue(top.next()); assertEquals(2, top.field)
        val r = top.readMessage()
        val seen = HashMap<Int, Any>()
        while (r.next()) seen[r.field] = if (r.wireType == WIRE_LEN) r.readString() else r.readVarint()
        assertEquals("B", seen[1]); assertEquals(1L, seen[2]); assertEquals(7L, seen[3]); assertEquals(3L, seen[100])
    }

    @Test
    fun `packed repeated category list is accepted`() {
        val w = ProtoWriter()
        w.message(1) {
            varint(1, 1); string(2, "/a")
            bytes(17, byteArrayOf(1, 2, 3))
        }
        assertEquals(listOf(1L, 2L, 3L), TachibkCodec.decode(w.toByteArray()).backupManga.single().categories)
    }

    @Test
    fun `round trips through encode and decode`() {
        val original = TachibkCodec.decode(handBuilt())
        val again = TachibkCodec.decode(TachibkCodec.encode(original))
        assertEquals(original, again)
    }

    @Test
    fun `accepts gzip and raw protobuf`() {
        val raw = handBuilt()
        val fromRaw = TachibkCodec.read(ByteArrayInputStream(raw))
        val fromGzip = TachibkCodec.read(ByteArrayInputStream(gzip(raw)))
        assertEquals(fromRaw, fromGzip)
        assertEquals(2, fromRaw.backupManga.size)
    }

    @Test
    fun `write produces gzip that read accepts`() {
        val bos = ByteArrayOutputStream()
        TachibkCodec.write(TachibkCodec.decode(handBuilt()), bos)
        val out = bos.toByteArray()
        assertEquals(0x1F, out[0].toInt() and 0xFF); assertEquals(0x8B, out[1].toInt() and 0xFF)
        assertEquals(TachibkCodec.decode(handBuilt()), TachibkCodec.read(ByteArrayInputStream(out)))
    }

    @Test
    fun `rejects json empty and truncated input`() {
        for (bad in listOf("{\"backupManga\":[]}".toByteArray(), ByteArray(0))) {
            try { TachibkCodec.read(ByteArrayInputStream(bad)); fail("expected IOException") } catch (_: IOException) {}
        }
        val truncated = handBuilt().copyOf(40)
        try { TachibkCodec.decode(truncated); fail("expected failure") } catch (_: ProtoFormatException) {}
    }

    @Test
    fun `chapter number survives as a float and unset strings stay null`() {
        val w = ProtoWriter()
        w.message(1) { varint(1, 1); string(2, "/a"); message(16) { string(1, "u"); string(2, "n") } }
        val m = TachibkCodec.decode(w.toByteArray()).backupManga.single()
        assertNull(m.author)
        assertNull(m.chapters.single().scanlator)
        assertArrayEquals(floatArrayOf(0f), floatArrayOf(m.chapters.single().chapterNumber), 0f)
    }
}
