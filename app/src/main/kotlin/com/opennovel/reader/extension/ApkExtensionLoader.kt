package com.opennovel.reader.extension

import android.content.Context
import android.content.pm.PackageManager
import com.opennovel.reader.source.Source
import dalvik.system.PathClassLoader

/**
 * Adapter for the APK-based ecosystems: **Mihon/Tachiyomi**, **IReader**, and
 * **Manatan**. All three ship extensions as installable Android packages that
 * expose source classes, discovered by scanning installed packages for a
 * ecosystem-specific metadata feature flag.
 *
 * Loading flow (per ecosystem, differing only in the feature key + API classes):
 *  1. `PackageManager.getInstalledPackages(GET_META_DATA)` and keep packages
 *     declaring the ecosystem's `<meta-data>` marker (e.g. Tachiyomi's
 *     `tachiyomi.extension` / `tachiyomi.extension.class`).
 *  2. Create a `PathClassLoader`/`DexClassLoader` over the extension APK.
 *  3. Instantiate the declared source/factory class.
 *  4. Wrap the ecosystem's native Source in an adapter that maps its API onto
 *     our [Source] contract.
 *
 * IMPORTANT compatibility caveats:
 *  - **Mihon/Tachiyomi & Manatan** extensions target the *manga* API — their
 *    "chapters" return image page URLs, not novel text. They are usable for
 *    manga/webtoon content; for text novels prefer IReader/LNReader sources.
 *  - The ecosystem's API classes (e.g. `eu.kanade.tachiyomi.source.*`) must be
 *    present at runtime. We provide thin `compileOnly`-style shim interfaces so
 *    the extension's dex resolves against our loader.
 *
 * This class defines the seam and metadata keys; the DexClassLoader wiring and
 * per-API adapters are the remaining implementation (see README).
 */
class ApkExtensionLoader(
    private val context: Context,
    override val ecosystem: Ecosystem,
    /**
     * Approvals for extension code. Null keeps every extension trusted, which is
     * only appropriate in tests — the app always supplies a real store.
     */
    private val trustStore: ExtensionTrustStore? = null,
) : ExtensionLoader {

    private val featureKey: String = when (ecosystem) {
        Ecosystem.MIHON -> "tachiyomi.extension"
        Ecosystem.IREADER -> "ireader.extension"
        Ecosystem.MANATAN -> "manatan.extension"
        else -> error("ApkExtensionLoader only handles APK ecosystems")
    }

    private val classKey = "$featureKey.class"

    override suspend fun listAvailable(repoUrl: String): List<ExtensionInfo> {
        // Available extensions come from the ecosystem's index repo (index.min.json).
        // Parsing is ecosystem-specific; returns downloadable-but-not-installed entries.
        return emptyList()
    }

    override suspend fun listInstalled(): List<ExtensionInfo> {
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS
        val packages = pm.getInstalledPackagesCompat(flags)
        return packages.mapNotNull { pkg ->
            val meta = pkg.applicationInfo?.metaData ?: return@mapNotNull null
            if (!meta.containsKey(featureKey)) return@mapNotNull null
            val signature = pm.signatureHashOf(pkg.packageName)
            ExtensionInfo(
                pkgId = pkg.packageName,
                name = pkg.applicationInfo!!.loadLabel(pm).toString(),
                lang = meta.getString("$featureKey.lang", "all"),
                versionName = pkg.versionName ?: "?",
                ecosystem = ecosystem,
                installed = true,
                artifact = pkg.applicationInfo!!.sourceDir,
                signatureHash = signature,
                trusted = trustStore == null || signature != null &&
                    trustStore.isTrusted(pkg.packageName, signature),
            )
        }
    }

    override suspend fun load(info: ExtensionInfo): List<Source> {
        // Untrusted extensions are never instantiated: loading is what executes
        // their code, so the check has to happen before class loading, not at
        // first use.
        if (trustStore != null) {
            val signature = info.signatureHash ?: return emptyList()
            if (!trustStore.isTrusted(info.pkgId, signature)) return emptyList()
        }

        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA
        val appInfo = pm.getApplicationInfoCompat(info.pkgId, flags)
        val meta = appInfo.metaData ?: return emptyList()

        // Class list is semicolon-separated; a leading '.' is relative to the package.
        val classNames = meta.getString(classKey).orEmpty()
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.startsWith(".")) info.pkgId + it else it }
        if (classNames.isEmpty()) return emptyList()

        val loader = PathClassLoader(appInfo.sourceDir, appInfo.nativeLibraryDir, javaClass.classLoader)

        return classNames.flatMap { className ->
            runCatching {
                val instance = Class.forName(className, false, loader)
                    .getDeclaredConstructor()
                    .newInstance()
                // A single Source, or a SourceFactory that yields many.
                when (instance) {
                    is eu.kanade.tachiyomi.source.SourceFactory ->
                        instance.createSources().map { MihonSourceAdapter(it, ecosystem) }
                    is eu.kanade.tachiyomi.source.Source ->
                        listOf(MihonSourceAdapter(instance, ecosystem))
                    else -> emptyList()
                }
            }.getOrElse { emptyList() }
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.getApplicationInfoCompat(pkg: String, flags: Int) =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(flags.toLong()))
        } else {
            getApplicationInfo(pkg, flags)
        }

    @Suppress("DEPRECATION")
    private fun PackageManager.getInstalledPackagesCompat(flags: Int) =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            getInstalledPackages(flags)
        }
}
