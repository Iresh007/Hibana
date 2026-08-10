package com.opennovel.reader.extension

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the three repository index shapes the app has to read.
 *
 * These fixtures are trimmed copies of real responses. The manifest one matters
 * most: Keiyoushi moved to it and left the old `index.min.json` in place holding
 * placeholder entries, so an app still reading the old file showed a catalogue
 * of two dead extensions and 404'd on install.
 */
class RepoIndexParserTest {

    private val parser = RepoIndexParser(OkHttpClient())
    private val repo = ExtensionRepo("https://example.test/repo/index.json", enabled = true)

    @Test
    fun `manifest format yields absolute artifact urls`() {
        val body = """
            {
              "name": "Keiyoushi",
              "extensionList": {
                "extensions": [
                  {
                    "name": "AHottie",
                    "packageName": "eu.kanade.tachiyomi.extension.all.ahottie",
                    "resources": {
                      "apkUrl": "https://cdn.example.test/apk/tachiyomi-all.ahottie-v1.6.4.apk",
                      "iconUrl": "https://cdn.example.test/icon.png"
                    },
                    "versionName": "1.6.4",
                    "contentWarning": "CONTENT_WARNING_NSFW",
                    "sources": [ { "id": "1", "name": "AHottie", "language": "all" } ]
                  }
                ]
              }
            }
        """.trimIndent()

        val result = parser.parse(body, repo)

        assertEquals(1, result.size)
        val ext = result.single()
        assertEquals("eu.kanade.tachiyomi.extension.all.ahottie", ext.pkgId)
        assertEquals("1.6.4", ext.version)
        // Taken verbatim, not resolved against the index url — resolving it would
        // rewrite a working CDN link into a 404.
        assertEquals("https://cdn.example.test/apk/tachiyomi-all.ahottie-v1.6.4.apk", ext.artifact)
        assertNotNull(ext.iconUrl)
        assertTrue(ext.nsfw)
    }

    @Test
    fun `manifest entry spanning languages reports all`() {
        val body = """
            {"extensionList":{"extensions":[{
              "name":"Multi","packageName":"x.y.z",
              "resources":{"apkUrl":"https://cdn.example.test/a.apk"},
              "versionName":"1.0",
              "contentWarning":"CONTENT_WARNING_SAFE",
              "sources":[{"language":"en"},{"language":"fr"}]
            }]}}
        """.trimIndent()

        val ext = parser.parse(body, repo).single()

        assertEquals("all", ext.lang)
        assertTrue(!ext.nsfw)
    }

    @Test
    fun `legacy tachiyomi array still parses`() {
        val body = """
            [{"name":"Tachiyomi: MangaDex","pkg":"eu.kanade.tachiyomi.extension.all.mangadex",
              "apk":"tachiyomi-all.mangadex-v1.4.87.apk","lang":"all","version":"1.4.87","nsfw":0}]
        """.trimIndent()

        val ext = parser.parse(body, repo).single()

        assertEquals("MangaDex", ext.name)
        assertEquals(Ecosystem.MIHON, ext.ecosystem)
        // A bare filename lives under the repo's apk/ directory; resolving it as
        // a plain sibling of the index drops that segment and 404s.
        assertEquals(
            "https://example.test/repo/apk/tachiyomi-all.mangadex-v1.4.87.apk",
            ext.artifact,
        )
    }

    @Test
    fun `lnreader plugin array still parses`() {
        val body = """
            [{"id":"novelupdates","name":"Novel Updates","lang":"English",
              "version":"1.0.2","url":"https://cdn.example.test/plugin.js",
              "iconUrl":"https://cdn.example.test/i.png"}]
        """.trimIndent()

        val ext = parser.parse(body, repo).single()

        assertEquals(Ecosystem.LNREADER, ext.ecosystem)
        assertEquals("https://cdn.example.test/plugin.js", ext.artifact)
    }

    @Test
    fun `malformed body yields nothing rather than throwing`() {
        assertTrue(parser.parse("not json at all", repo).isEmpty())
        assertTrue(parser.parse("", repo).isEmpty())
    }

    @Test
    fun `version comparison is numeric per segment`() {
        // String ordering puts "1.4.9" above "1.4.10", which would offer a
        // downgrade as though it were an update.
        assertTrue(compareVersions("1.4.10", "1.4.9") > 0)
        assertTrue(compareVersions("1.5", "1.4.99") > 0)
        assertEquals(0, compareVersions("2.0.0", "2.0.0"))
    }
}
