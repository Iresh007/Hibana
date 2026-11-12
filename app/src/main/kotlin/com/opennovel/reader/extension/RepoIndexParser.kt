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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

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
    /** Extension-lib version the APK targets ("1.4"/"1.6"), when the index says. */
    val libVersion: String? = null,
    val versionCode: Long = 0,
    /**
     * SHA-256 fingerprint (hex) of the certificate every extension in this store is signed with, as
     * published by the store's index. Installs are refused when the APK is signed with anything else.
     */
    val repoSigningKey: String? = null,
    /** Sources the extension declares (Mihon index.pb / manifest only). */
    val sources: List<RepoSource> = emptyList(),
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

/** A source declared by an extension in a store index. */
data class RepoSource(val id: Long, val name: String, val lang: String, val baseUrl: String)

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
 * Four shapes exist in the wild and all must work, since the app supports
 * several ecosystems from one repo list:
 *  - **`index.pb`** (Mihon 0.21 "extension store", e.g. Keiyoushi): optionally gzip-compressed
 *    protobuf. Schema confirmed against Mihon v0.21.3 `NetworkExtensionStore`: top level 1 name,
 *    2 badge label, 3 signing-key fingerprint, 4 contact, 101 extension list (repeated field 1 =
 *    Extension), 102 external extension-list url. See [parseStoreProto].
 *  - **Repo manifest** (Keiyoushi `index.json`): the same data as JSON, `extensionList.extensions[]`,
 *    each carrying absolute `resources.apkUrl` / `resources.iconUrl`.
 *  - **Legacy Tachiyomi** `index.min.json`: an array of `{name, pkg, apk, lang, version, nsfw}`.
 *  - **LNReader** `plugins.min.json`: an array of `{id, name, site, lang, version, url, iconUrl}`.
 *
 * They're told apart by their content (first byte / structure) rather than by URL, so a mirror or
 * self-hosted copy still parses correctly.
 */
class RepoIndexParser(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(repo: ExtensionRepo): List<RepoExtension> = withContext(Dispatchers.IO) {
        val primary = parseOrEmpty(download(repo.url), repo, allowExternalList = true)
        if (primary.isNotEmpty()) return@withContext primary

        // The legacy/JSON index is often left behind as a stub (placeholder entries only, which
        // parse() drops) once a repo moves to index.pb; try the sibling protobuf index.
        val sibling = siblingProtoUrl(repo.url) ?: return@withContext emptyList()
        parseOrEmpty(download(sibling), repo.copy(url = sibling), allowExternalList = true)
    }

    private fun download(url: String): ByteArray? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) readLimited(response.body?.byteStream()) else null
        }
    }.getOrNull()

    private fun parseOrEmpty(bytes: ByteArray?, repo: ExtensionRepo, allowExternalList: Boolean): List<RepoExtension> {
        bytes ?: return emptyList()
        val index = runCatching { parseIndex(bytes, repo) }.getOrNull() ?: return emptyList()
        if (index.extensions.isNotEmpty() || !allowExternalList) return index.extensions
        // Stores may keep the (large) extension list in a separate file.
        val listUrl = index.extensionListUrl?.takeIf { it.startsWith("https://") } ?: return emptyList()
        val listBytes = download(listUrl) ?: return emptyList()
        return runCatching {
            val body = unzip(listBytes)
            if (body.firstOrNull() == '{'.code.toByte()) {
                val array = json.parseToJsonElement(body.decodeToString()).jsonObject["extensions"] as? JsonArray
                parseManifestExtensions(array ?: JsonArray(emptyList()), repo, index.signingKey)
            } else {
                parseExtensionList(body, repo, index.signingKey)
            }
        }.getOrDefault(emptyList())
    }

    /** `…/index.json` or `…/index.min.json` -> `…/index.pb`; null when [url] is not such a file. */
    internal fun siblingProtoUrl(url: String): String? {
        val clean = url.substringBefore('?')
        return when {
            clean.endsWith("/index.json") -> clean.removeSuffix("index.json") + "index.pb"
            clean.endsWith("/index.min.json") -> clean.removeSuffix("index.min.json") + "index.pb"
            else -> null
        }
    }

    private class StoreIndex(
        val signingKey: String?,
        val extensions: List<RepoExtension>,
        val extensionListUrl: String?,
    )

    /**
     * Parses a raw index body of any supported shape, gunzipping first when needed. Dispatches on
     * the first byte like Mihon: `[` legacy array, `{` JSON object, anything else protobuf.
     */
    fun parse(bytes: ByteArray, repo: ExtensionRepo): List<RepoExtension> =
        runCatching { parseIndex(bytes, repo).extensions }.getOrDefault(emptyList())

    private fun parseIndex(raw: ByteArray, repo: ExtensionRepo): StoreIndex {
        val bytes = unzip(raw)
        if (bytes.isEmpty()) return StoreIndex(null, emptyList(), null)
        return when (bytes[0]) {
            '['.code.toByte(), '{'.code.toByte() ->
                StoreIndex(null, parse(bytes.decodeToString(), repo), null)
            else -> parseStoreProto(bytes, repo)
        }
    }

    /** Gunzips when the 0x1f8b magic is present; otherwise returns the input. Output is size-capped. */
    private fun unzip(bytes: ByteArray): ByteArray {
        if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) return bytes
        return readLimited(GZIPInputStream(ByteArrayInputStream(bytes))) ?: ByteArray(0)
    }

    private fun readLimited(stream: InputStream?): ByteArray? {
        stream ?: return null
        stream.use {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_INDEX_BYTES) { "Index is unreasonably large" }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    // --- protobuf (index.pb) ---

    private fun parseStoreProto(bytes: ByteArray, repo: ExtensionRepo): StoreIndex {
        var signingKey: String? = null
        var listUrl: String? = null
        var listBytes: ByteArray? = null
        for (field in ProtoWire.read(bytes)) {
            when (field.number) {
                3 -> signingKey = field.string()
                101 -> listBytes = field.bytes
                102 -> listUrl = field.string().takeIf { it.isNotBlank() }
            }
        }
        val extensions = listBytes?.let { parseExtensionList(it, repo, signingKey) }.orEmpty()
        return StoreIndex(signingKey, extensions, listUrl)
    }

    /** `ExtensionList { repeated Extension extensions = 1; }` */
    private fun parseExtensionList(bytes: ByteArray, repo: ExtensionRepo, signingKey: String?): List<RepoExtension> =
        ProtoWire.read(bytes)
            .filter { it.number == 1 && it.bytes != null }
            .mapNotNull { runCatching { parseProtoExtension(it.bytes!!, repo, signingKey) }.getOrNull() }

    private fun parseProtoExtension(bytes: ByteArray, repo: ExtensionRepo, signingKey: String?): RepoExtension? {
        var name = ""
        var pkg = ""
        var apk = ""
        var icon = ""
        var lib: String? = null
        var versionCode = 0L
        var versionName = ""
        var contentWarning = 0L
        val sources = mutableListOf<RepoSource>()
        for (field in ProtoWire.read(bytes)) {
            when (field.number) {
                1 -> name = field.string()
                2 -> pkg = field.string()
                3 -> for (res in ProtoWire.read(field.bytes ?: ByteArray(0))) {
                    when (res.number) {
                        1 -> apk = res.string()
                        2 -> icon = res.string()
                    }
                }
                4 -> lib = field.string().takeIf { it.isNotBlank() }
                5 -> versionCode = field.varint
                6 -> versionName = field.string()
                7 -> contentWarning = field.varint
                8 -> sources += parseProtoSource(field.bytes ?: ByteArray(0))
            }
        }
        if (pkg.isBlank() || apk.isBlank()) return null

        val languages = sources.map { it.lang }.filter { it.isNotBlank() }.distinct()
        return RepoExtension(
            pkgId = pkg,
            name = name.removePrefix("Tachiyomi: ").ifBlank { pkg },
            lang = languages.singleOrNull() ?: "all",
            version = versionName.ifBlank { "?" },
            ecosystem = Ecosystem.MIHON,
            artifact = apk,
            iconUrl = icon.takeIf { it.isNotBlank() },
            // 0 unspecified, 1 safe, 2 mixed, 3 NSFW. Mihon treats unspecified as safe.
            nsfw = contentWarning == 2L || contentWarning == 3L,
            repoUrl = repo.url,
            libVersion = lib,
            versionCode = versionCode,
            repoSigningKey = signingKey?.takeIf { it.isNotBlank() },
            sources = sources,
        )
    }

    private fun parseProtoSource(bytes: ByteArray): RepoSource {
        var id = 0L
        var name = ""
        var lang = ""
        var home = ""
        for (field in ProtoWire.read(bytes)) {
            when (field.number) {
                1 -> id = field.varint
                2 -> name = field.string()
                3 -> lang = field.string()
                4 -> home = field.string()
            }
        }
        return RepoSource(id, name, lang, home)
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
            }.filterNot { isPlaceholder(it) }
        }.getOrDefault(emptyList())
    }

    /**
     * Stub entries a repo leaves in its legacy `index.min.json` after migrating ("Outdated App",
     * "Update to Mihon 0.20.1+"). They have no downloadable APK and must not look like a catalogue.
     */
    private fun isPlaceholder(ext: RepoExtension): Boolean =
        ext.name == "Outdated App" || ext.name.startsWith("Update to Mihon")

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
        val signingKey = root["signingKey"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return parseManifestExtensions(extensions, repo, signingKey)
    }

    private fun parseManifestExtensions(
        extensions: JsonArray,
        repo: ExtensionRepo,
        signingKey: String?,
    ): List<RepoExtension> {
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
                libVersion = str("extensionLib"),
                versionCode = o["versionCode"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                repoSigningKey = signingKey,
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

    private companion object {
        /** Guards against decompression bombs / hostile indexes (the Keiyoushi index is ~0.8 MB raw). */
        const val MAX_INDEX_BYTES = 64 * 1024 * 1024
    }
}

/**
 * Minimal protobuf wire-format reader: enough for index.pb, tolerant of unknown fields (the index
 * carries some Mihon doesn't model, e.g. resources field 501) and strict about truncation so a
 * corrupted download fails instead of yielding half an index.
 */
internal object ProtoWire {

    class Field(val number: Int, val varint: Long, val bytes: ByteArray?) {
        fun string(): String = (bytes ?: ByteArray(0)).decodeToString()
    }

    fun read(data: ByteArray): List<Field> {
        val fields = ArrayList<Field>()
        var pos = 0

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(pos < data.size) { "Truncated varint" }
                require(shift < 64) { "Varint too long" }
                val b = data[pos++].toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        while (pos < data.size) {
            val key = varint()
            val number = (key ushr 3).toInt()
            require(number > 0) { "Invalid field number" }
            when ((key and 7).toInt()) {
                0 -> fields += Field(number, varint(), null)
                1 -> {
                    require(pos + 8 <= data.size) { "Truncated fixed64" }
                    pos += 8
                }
                2 -> {
                    val length = varint()
                    require(length >= 0 && length <= data.size - pos) { "Truncated length-delimited field" }
                    fields += Field(number, 0, data.copyOfRange(pos, pos + length.toInt()))
                    pos += length.toInt()
                }
                5 -> {
                    require(pos + 4 <= data.size) { "Truncated fixed32" }
                    pos += 4
                }
                else -> throw IllegalArgumentException("Unsupported wire type")
            }
        }
        return fields
    }
}
