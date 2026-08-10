package com.opennovel.reader.extension

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
 * Three shapes exist in the wild and all must work, since the app supports
 * several ecosystems from one repo list:
 *  - **Repo manifest** (current Keiyoushi): an object with `extensionList.extensions[]`,
 *    each carrying absolute `resources.apkUrl` / `resources.iconUrl`.
 *  - **Legacy Tachiyomi** `index.min.json`: an array of `{name, pkg, apk, lang, version, nsfw}`.
 *  - **LNReader** `plugins.min.json`: an array of `{id, name, site, lang, version, url, iconUrl}`.
 *
 * They're told apart by their structure rather than by URL, so a mirror or
 * self-hosted copy still parses correctly.
 */
class RepoIndexParser(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(repo: ExtensionRepo): List<RepoExtension> = withContext(Dispatchers.IO) {
        val body = runCatching {
            client.newCall(Request.Builder().url(repo.url).build()).execute()
                .use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        }.getOrDefault("")
        parse(body, repo)
    }

    /**
     * Split from [fetch] so index formats can be covered by JVM unit tests. A
     * repo silently changing shape breaks installs for every user at once, and
     * that is not something to find out from a bug report.
     */
    fun parse(body: String, repo: ExtensionRepo): List<RepoExtension> {
        if (body.isBlank()) return emptyList()

        return runCatching {
            val root = json.parseToJsonElement(body)

            // A manifest object rather than a bare array means the newer repo
            // format. Repos that have migrated leave the old index.min.json in
            // place holding only "Outdated App" placeholders, whose APKs are
            // deliberately absent — parsing that as a catalogue is why installs
            // came back 404 for extensions that were perfectly fine.
            (root as? JsonObject)?.let { return@runCatching parseManifest(it, repo) }

            val array = root as? JsonArray ?: return@runCatching emptyList()
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
                        artifact = str("apk")?.let { apk -> resolveApk(repo.url, apk) }.orEmpty(),
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

    /**
     * Parses the repo-manifest format, where every entry already carries
     * absolute artifact URLs so nothing has to be resolved against the index.
     *
     * An entry can declare several sources; its language is reported as the
     * single language when they agree and "all" when they don't, which is how
     * the multi-language bundles describe themselves.
     */
    private fun parseManifest(root: JsonObject, repo: ExtensionRepo): List<RepoExtension> {
        val extensions = root["extensionList"]?.jsonObject
            ?.get("extensions") as? JsonArray
            ?: return emptyList()

        return extensions.mapNotNull { element ->
            val o = element.jsonObject
            fun str(key: String) = o[key]?.jsonPrimitive?.contentOrNull

            val pkg = str("packageName") ?: return@mapNotNull null
            val resources = o["resources"]?.jsonObject
            val apkUrl = resources?.get("apkUrl")?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null

            val languages = (o["sources"] as? JsonArray)
                ?.mapNotNull { it.jsonObject["language"]?.jsonPrimitive?.contentOrNull }
                ?.distinct()
                .orEmpty()

            RepoExtension(
                pkgId = pkg,
                name = str("name") ?: pkg,
                lang = languages.singleOrNull() ?: "all",
                version = str("versionName") ?: "?",
                ecosystem = Ecosystem.MIHON,
                artifact = apkUrl,
                iconUrl = resources["iconUrl"]?.jsonPrimitive?.contentOrNull,
                // Mixed repos flag per entry; treat anything not explicitly safe
                // as adult so the NSFW filter errs toward hiding.
                nsfw = str("contentWarning")?.let { it != "CONTENT_WARNING_SAFE" } ?: false,
                repoUrl = repo.url,
            )
        }
    }

    /** Resolves a repo-relative path against the index URL's directory. */
    private fun resolve(indexUrl: String, path: String): String =
        if (path.startsWith("http")) path else indexUrl.substringBeforeLast('/') + "/" + path.trimStart('/')

    /**
     * Resolves the APK download URL for a Tachiyomi-style index entry.
     *
     * The `apk` field carries a bare filename, but repos serve builds from an
     * `apk/` directory beside the index — resolving it as a plain relative path
     * drops that segment and every install 404s even though the extension is
     * fine. Entries that already spell out a path or a full URL are left alone,
     * so mirrors that lay their files out differently still work.
     */
    private fun resolveApk(indexUrl: String, apk: String): String = when {
        apk.startsWith("http") -> apk
        apk.contains('/') -> resolve(indexUrl, apk)
        else -> resolve(indexUrl, "apk/$apk")
    }
}
