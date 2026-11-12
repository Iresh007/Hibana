package com.opennovel.reader.extension

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** index.pb (Mihon 0.21 extension store) parsing, with protobuf built by hand so no codegen is needed. */
class RepoIndexPbTest {

    private val parser = RepoIndexParser(OkHttpClient())
    private val repo = ExtensionRepo("https://example.test/repo/index.pb")

    // --- tiny protobuf writer ---
    private fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (true) {
            if (v and 0x7fL.inv() == 0L) { out.write(v.toInt()); break }
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        return out.toByteArray()
    }
    private fun key(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
    private fun str(field: Int, value: String) = bytes(field, value.toByteArray())
    private fun bytes(field: Int, value: ByteArray) = key(field, 2) + varint(value.size.toLong()) + value
    private fun num(field: Int, value: Long) = key(field, 0) + varint(value)
    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun source(id: Long, name: String, lang: String, home: String) =
        num(1, id) + str(2, name) + str(3, lang) + str(4, home)

    private fun extension(
        name: String, pkg: String, apk: String, versionCode: Long, versionName: String,
        cw: Long, sources: List<ByteArray>,
    ): ByteArray {
        val resources = str(1, apk) + str(2, "https://cdn.example.test/icon.png") + str(501, "unknown-field")
        return str(1, name) + str(2, pkg) + bytes(3, resources) + str(4, "1.6") +
            num(5, versionCode) + str(6, versionName) + num(7, cw) +
            sources.fold(ByteArray(0)) { acc, s -> acc + bytes(8, s) }
    }

    private fun store(vararg exts: ByteArray): ByteArray {
        val list = exts.fold(ByteArray(0)) { acc, e -> acc + bytes(1, e) }
        return str(1, "Test Store") + str(2, "TST") + str(3, "ABCDEF0123") +
            bytes(4, str(1, "https://example.test")) + bytes(101, list)
    }

    private val sample = store(
        extension(
            "Tachiyomi: One", "eu.kanade.tachiyomi.extension.en.one", "https://cdn.example.test/one.apk",
            106005, "1.6.5", 1, listOf(source(42L, "One", "en", "https://one.test")),
        ),
        extension(
            "Multi", "eu.kanade.tachiyomi.extension.all.multi", "https://cdn.example.test/multi.apk",
            106001, "1.6.1", 3,
            listOf(source(1L, "Multi", "en", "https://m.test"), source(2L, "Multi", "ja", "https://m.test")),
        ),
        // No APK url: unusable, must be skipped.
        extension("Broken", "x.broken", "", 1, "1.6.1", 1, emptyList()),
    )

    @Test
    fun `gzip compressed index parses`() {
        val result = parser.parse(gzip(sample), repo)
        assertEquals(2, result.size)
        val one = result[0]
        assertEquals("eu.kanade.tachiyomi.extension.en.one", one.pkgId)
        assertEquals("One", one.name) // "Tachiyomi: " stripped
        assertEquals("en", one.lang)
        assertEquals("1.6.5", one.version)
        assertEquals(106005L, one.versionCode)
        assertEquals("1.6", one.libVersion)
        assertEquals("https://cdn.example.test/one.apk", one.artifact)
        assertEquals("https://cdn.example.test/icon.png", one.iconUrl)
        assertEquals("ABCDEF0123", one.repoSigningKey)
        assertEquals(Ecosystem.MIHON, one.ecosystem)
        assertFalse(one.nsfw)
        assertEquals(42L, one.sources.single().id)
    }

    @Test
    fun `raw uncompressed index parses and nsfw plus multi language map`() {
        val multi = parser.parse(sample, repo)[1]
        assertEquals("all", multi.lang)
        assertTrue(multi.nsfw)
        assertEquals(2, multi.sources.size)
    }

    @Test
    fun `truncated protobuf yields nothing instead of throwing`() {
        assertTrue(parser.parse(sample.copyOf(sample.size - 5), repo).isEmpty())
    }

    @Test
    fun `garbage and empty yield nothing`() {
        assertTrue(parser.parse(ByteArray(0), repo).isEmpty())
        assertTrue(parser.parse(byteArrayOf(0x1f, 0x8b.toByte(), 1, 2, 3), repo).isEmpty())
    }

    @Test
    fun `json bytes are still routed to the json parser`() {
        val json = """[{"name":"Tachiyomi: X","pkg":"p.x","apk":"x.apk","lang":"en","version":"1.4.1","nsfw":0}]"""
        assertEquals("p.x", parser.parse(json.toByteArray(), repo).single().pkgId)
    }

    @Test
    fun `legacy placeholder stub parses to nothing`() {
        val stub = """[{"name":"Outdated App","pkg":"a.b","apk":"a.apk","lang":"all","version":"1.4.1"},
            {"name":"Update to Mihon 0.20.1+","pkg":"a.c","apk":"c.apk","lang":"all","version":"1.4.1"}]"""
        assertTrue(parser.parse(stub, ExtensionRepo("https://example.test/index.min.json")).isEmpty())
    }

    @Test
    fun `sibling proto url is derived only for json index files`() {
        assertEquals("https://h/r/index.pb", parser.siblingProtoUrl("https://h/r/index.json"))
        assertEquals("https://h/r/index.pb", parser.siblingProtoUrl("https://h/r/index.min.json"))
        assertNull(parser.siblingProtoUrl("https://h/r/index.pb"))
    }

    @Test
    fun `real keiyoushi index fixture parses`() {
        val bytes = javaClass.getResourceAsStream("/keiyoushi-index.pb")!!.use { it.readBytes() }
        val result = parser.parse(bytes, repo)
        assertTrue("expected >1000 extensions, got ${result.size}", result.size > 1000)
        val first = result.first()
        assertEquals("eu.kanade.tachiyomi.extension.all.ahottie", first.pkgId)
        assertEquals("1.6", first.libVersion)
        assertTrue(first.artifact.startsWith("https://github.com/keiyoushi/extensions/releases/download/"))
        assertEquals(
            "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
            first.repoSigningKey,
        )
        assertTrue(first.nsfw)
    }
}
