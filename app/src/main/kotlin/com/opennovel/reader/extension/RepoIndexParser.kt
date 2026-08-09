package com.opennovel.reader.extension

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** An extension offered by a repository, plus whether it's installed/updatable. */
data class RepoExtension(
    val pkgId: String,
    val name: String,
    val lang: String,
    val version: String,
    val ecosystem: Ecosystem,
    /** Download URL: an APK for Mihon-style repos, a .js file for LNReader. */
    val artifact: String,
    val iconUrl: String? = null,
    val nsfw: Boolean = false,
    val repoUrl: String,
    val installedVersion: String? = null,
) {
    val installed: Boolean get() = installedVersion != null

    /**
     * True when the repo offers a newer build than what's installed. Versions are
     * compared numerically per segment — "1.4.10" is newer than "1.4.9", which a
     * plain string comparison gets backwards.
     */
    val hasUpdate: Boolean
        get() {
            val installed = installedVersion ?: return false
            return compareVersions(version, installed) > 0
        }
}

/** Compares dotted version strings segment by segment. */
fun compareVersions(a: String, b: String): Int {
    val left = a.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    val right = b.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(left.size, right.size)) {
        val l = left.getOrElse(i) { 0 }
        val r = right.getOrElse(i) { 0 }
        if (l != r) return l.compareTo(r)
    }
    return 0
}

/**
 * Reads extension repository indexes.
 *
 * Two shapes exist in the wild and both must work, since the app supports both
 * ecosystems from one repo list:
 *  - **Mihon/Tachiyomi** `index.min.json`: `{name, pkg, apk, lang, version, nsfw, sources[]}`
 *  - **LNReader** `plugins.min.json`: `{id, name, site, lang, version, url, iconUrl}`
 *
 * They're told apart by which keys are present rather than by URL, so a mirror or
 * self-hosted copy still parses correctly.
 */
class RepoIndexParser(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(repo: ExtensionRepo): List<RepoExtension> = withContext(Dispatchers.IO) {
        val body = runCatching {
            client.newCall(Request.Builder().url(repo.url).build()).execute()
                .use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        }.getOrDefault("")
        if (body.isBlank()) return@withContext emptyList()

        runCatching {
            val array = json.parseToJsonElement(body) as? JsonArray ?: return@runCatching emptyList()
            array.mapNotNull { element ->
                val o = element.jsonObject
                fun str(key: String) = o[key]?.jsonPrimitive?.contentOrNull

                // "pkg" identifies a Tachiyomi-style APK entry; "id" an LNReader plugin.
                val pkg = str("pkg")
                if (pkg != null) {
                    RepoExtension(
                        pkgId = pkg,
                        name = str("name")?.removePrefix("Tachiyomi: ") ?: pkg,
                        lang = str("lang") ?: "all",
                        version = str("version") ?: "?",
                        ecosystem = Ecosystem.MIHON,
                        // APK paths are relative to the index file's directory.
                        artifact = str("apk")?.let { apk -> resolve(repo.url, apk) }.orEmpty(),
                        iconUrl = str("pkg")?.let { p ->
                            resolve(repo.url, "icon/$p.png")
                        },
                        nsfw = (o["nsfw"]?.jsonPrimitive?.intOrNull ?: 0) == 1,
                        repoUrl = repo.url,
                    )
                } else {
                    val id = str("id") ?: return@mapNotNull null
                    RepoExtension(
                        pkgId = id,
                        name = str("name") ?: id,
                        lang = str("lang") ?: "all",
                        version = str("version") ?: "?",
                        ecosystem = Ecosystem.LNREADER,
                        artifact = str("url").orEmpty(),
                        iconUrl = str("iconUrl"),
                        repoUrl = repo.url,
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Resolves a repo-relative path against the index URL's directory. */
    private fun resolve(indexUrl: String, path: String): String =
        if (path.startsWith("http")) path else indexUrl.substringBeforeLast('/') + "/" + path.trimStart('/')
}
