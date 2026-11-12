package com.opennovel.reader.extension

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.opennovel.reader.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import eu.kanade.tachiyomi.source.Source as NativeSource

/**
 * Loader for **Mihon / Tachiyomi** manga extensions (and Manatan's, which uses the very same
 * `tachiyomi.*` keys). Behaviour follows Mihon v0.21.3's `ExtensionLoader`:
 *
 *  - Discovery: `<uses-feature android:name="tachiyomi.extension"/>` ([PackageInfo.reqFeatures],
 *    requested with `GET_CONFIGURATIONS`), then the application meta-data
 *    `tachiyomi.extension.class` / `.factory` / `.nsfw`, `tachiyomix.name`, `tachiyomix.extensionLib`
 *    (1.4 and 1.6 only) and `tachiyomix.contentWarning`. Anime extensions (`tachiyomi.animeextension*`)
 *    are filtered out.
 *  - Two kinds of packages: system-installed (shared) ones, loaded straight from their read-only
 *    `sourceDir`, and private ones copied into `files/exts` and marked read-only (Android 14+ refuses
 *    to load writable dex). When both exist the higher versionCode wins, shared on ties.
 *  - Child-first class loading ([ExtensionClassLoader]).
 *  - Signing certificate + [ExtensionTrustStore] approval are checked **before** any extension class
 *    is loaded. Failures are logged and kept per package for the UI ([loadErrorFor]).
 *
 * The extension-lib itself is the real Mihon source-api in `eu.kanade.tachiyomi.*`, with real Injekt
 * and RxJava on the class path; host singletons are registered by [ExtensionRuntime].
 *
 * The [Ecosystem.MANATAN] instance is intentionally empty: Manatan extensions are indistinguishable
 * from Mihon ones at the package level, so listing them twice would register every source twice.
 */
class ApkExtensionLoader(
    context: Context,
    override val ecosystem: Ecosystem,
    /**
     * Approvals for extension code. Null keeps every extension trusted, which is
     * only appropriate in tests — the app always supplies a real store.
     */
    private val trustStore: ExtensionTrustStore? = null,
    /** Used by [listAvailable]; defaults to a parser over the shared extension HTTP client. */
    private val indexParser: RepoIndexParser? = null,
) : ExtensionLoader, LoadErrorReporter {

    private val appContext: Context = context.applicationContext

    private val loadErrors = ConcurrentHashMap<String, String>()

    init {
        require(ecosystem == Ecosystem.MIHON || ecosystem == Ecosystem.MANATAN) {
            "ApkExtensionLoader only handles the Mihon/Manatan APK ecosystems"
        }
    }

    override fun loadErrorFor(pkgId: String): String? = loadErrors[pkgId]

    override suspend fun listAvailable(repoUrl: String): List<ExtensionInfo> {
        if (ecosystem != Ecosystem.MIHON) return emptyList()
        ExtensionRuntime.init(appContext)
        val parser = indexParser ?: RepoIndexParser(ExtensionRuntime.networkHelper().client)
        val installed = listInstalled().associateBy { it.pkgId }
        return parser.fetch(ExtensionRepo(repoUrl))
            .filter { it.ecosystem == Ecosystem.MIHON }
            .map { item ->
                ExtensionInfo(
                    pkgId = item.pkgId,
                    name = item.name,
                    lang = item.lang,
                    versionName = item.version,
                    ecosystem = ecosystem,
                    installed = item.pkgId in installed,
                    artifact = item.artifact,
                    libVersion = item.libVersion,
                    nsfw = item.nsfw,
                )
            }
    }

    override suspend fun listInstalled(): List<ExtensionInfo> {
        if (ecosystem != Ecosystem.MIHON) return emptyList()
        return withContext(Dispatchers.IO) {
            val pm = appContext.packageManager
            candidates(pm).map { candidate -> describe(pm, candidate) }
        }
    }

    override suspend fun load(info: ExtensionInfo): List<Source> {
        if (ecosystem != Ecosystem.MIHON) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                loadInternal(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Includes LinkageError (NoSuchMethodError...): an extension built against a
                // different API must fail alone, loudly, and visibly in the UI.
                fail(info.pkgId, e)
                emptyList()
            }
        }
    }

    // --- discovery ---

    private class Candidate(val pkg: PackageInfo, val isShared: Boolean)

    private fun candidates(pm: PackageManager): List<Candidate> {
        val flags = ExtensionApk.PACKAGE_FLAGS
        val installed = getInstalledPackagesCompat(pm, flags)
            .filter { ExtensionApk.isExtension(it) && !ExtensionApk.isAnimeExtension(it) }
            .map { Candidate(it, isShared = true) }

        val private = ExtensionApk.privateDir(appContext).listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "ext" }
            .mapNotNull { file ->
                // Just in case: Android 14+ requires loaded dex to be read-only.
                if (file.canWrite()) file.setReadOnly()
                ExtensionApk.archiveInfo(appContext, file)
            }
            .filter { ExtensionApk.isExtension(it) && !ExtensionApk.isAnimeExtension(it) }
            .map { Candidate(it, isShared = false) }

        return (installed + private)
            .groupBy { it.pkg.packageName }
            .values
            .map { group ->
                group.maxWithOrNull(
                    compareBy<Candidate> { PackageInfoCompat.getLongVersionCode(it.pkg) }
                        .thenBy { if (it.isShared) 1 else 0 },
                )!!
            }
            .sortedBy { it.pkg.packageName }
    }

    private suspend fun describe(pm: PackageManager, candidate: Candidate): ExtensionInfo {
        val pkg = candidate.pkg
        val hash = ExtensionApk.trustHash(pkg)
        val lib = ExtensionApk.libVersion(pkg)
        val problem = validate(pkg, lib, hash)
        return ExtensionInfo(
            pkgId = pkg.packageName,
            name = ExtensionApk.displayName(pm, pkg),
            lang = ExtensionApk.langFromPackage(pkg.packageName),
            versionName = pkg.versionName ?: "?",
            ecosystem = ecosystem,
            installed = true,
            artifact = pkg.applicationInfo?.sourceDir.orEmpty(),
            signatureHash = hash,
            trusted = trustStore == null || (hash != null && trustStore.isTrusted(pkg.packageName, hash)),
            loadError = problem ?: loadErrors[pkg.packageName],
            libVersion = lib?.toString(),
            nsfw = ExtensionApk.contentWarning(pkg) == 2,
            isShared = candidate.isShared,
        )
    }

    /** Cheap static checks shared by listing and loading; returns a user-facing reason or null. */
    private fun validate(pkg: PackageInfo, lib: Double?, hash: String?): String? = when {
        pkg.applicationInfo?.metaData == null -> "Malformed extension (no application meta-data)"
        pkg.versionName.isNullOrEmpty() -> "Malformed extension (no versionName)"
        lib == null || lib !in ExtensionApk.SUPPORTED_LIB_VERSIONS ->
            "Unsupported extension library version $lib (supported: " +
                ExtensionApk.SUPPORTED_LIB_VERSIONS.joinToString() + ")"
        hash == null -> "Extension is not signed"
        else -> null
    }

    // --- loading ---

    private suspend fun loadInternal(info: ExtensionInfo): List<Source> {
        ExtensionRuntime.init(appContext)
        val pm = appContext.packageManager

        val (pkg, isShared) = resolvePackage(pm, info)
            ?: return fail(info.pkgId, "Extension package not found: ${info.pkgId}")

        if (ExtensionApk.isAnimeExtension(pkg)) {
            return fail(info.pkgId, "Anime extensions are not supported")
        }

        // Everything is re-derived from the package itself rather than trusting `info`, which may
        // be stale or have been built by a caller.
        val hash = ExtensionApk.trustHash(pkg)
        val lib = ExtensionApk.libVersion(pkg)
        validate(pkg, lib, hash)?.let { return fail(info.pkgId, it) }

        // Untrusted extensions are never instantiated: loading is what executes
        // their code, so the check has to happen before class loading, not at first use.
        if (trustStore != null && !trustStore.isTrusted(pkg.packageName, hash!!)) {
            Log.w(ExtensionApk.TAG, "Extension ${pkg.packageName} isn't trusted; not loading")
            return emptyList()
        }

        val appInfo = pkg.applicationInfo!!
        val meta = appInfo.metaData
        val classNames = meta.getString(ExtensionApk.META_CLASS).orEmpty()
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.startsWith(".")) pkg.packageName + it else it }
        if (classNames.isEmpty()) return fail(info.pkgId, "Missing ${ExtensionApk.META_CLASS} meta-data")

        val apk = File(appInfo.sourceDir)
        if (!isShared && apk.canWrite()) apk.setReadOnly()

        val loader = ExtensionClassLoader(appInfo.sourceDir, null, appContext.classLoader)

        val sources = classNames.flatMap { className ->
            val instance = Class.forName(className, false, loader).getDeclaredConstructor().newInstance()
            when (instance) {
                is NativeSource -> listOf(instance)
                is SourceFactory -> instance.createSources()
                else -> throw IllegalStateException("Unknown source class type: ${instance.javaClass}")
            }
        }

        loadErrors.remove(info.pkgId)
        return sources.map { MihonSourceAdapter(it, ecosystem, pkg.packageName) }
    }

    /**
     * Finds the package to load: a private APK when [ExtensionInfo.artifact] points into
     * `files/exts` (or at a not-yet-copied APK, which is first copied there read-only), otherwise
     * the system-installed package. When both exist the higher versionCode wins.
     */
    private fun resolvePackage(pm: PackageManager, info: ExtensionInfo): Pair<PackageInfo, Boolean>? {
        val artifact = info.artifact.takeIf { it.endsWith(".ext") || it.endsWith(".apk") }?.let(::File)
        val privatePkg = (when {
            artifact != null && artifact.isFile && ExtensionApk.isPrivate(appContext, artifact.path) ->
                ExtensionApk.archiveInfo(appContext, artifact)
            artifact != null && artifact.isFile && !info.installed -> {
                // Not installed: load only from a private, read-only copy.
                val target = ExtensionApk.privateFile(appContext, info.pkgId)
                ExtensionApk.copyToPrivateReadOnly(artifact, target)
                ExtensionApk.archiveInfo(appContext, target)
            }
            else -> ExtensionApk.privateFile(appContext, info.pkgId).takeIf { it.isFile }
                ?.let { ExtensionApk.archiveInfo(appContext, it) }
        })?.takeIf { it.packageName == info.pkgId && ExtensionApk.isExtension(it) }

        val sharedPkg = try {
            getPackageInfoCompat(pm, info.pkgId, ExtensionApk.PACKAGE_FLAGS)
                .takeIf { ExtensionApk.isExtension(it) }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

        return when {
            sharedPkg == null && privatePkg == null -> null
            privatePkg == null -> sharedPkg!! to true
            sharedPkg == null -> privatePkg to false
            PackageInfoCompat.getLongVersionCode(sharedPkg) >= PackageInfoCompat.getLongVersionCode(privatePkg) ->
                sharedPkg to true
            else -> privatePkg to false
        }
    }

    private fun fail(pkgId: String, message: String): List<Source> {
        Log.w(ExtensionApk.TAG, "Extension load error: $pkgId: $message")
        loadErrors[pkgId] = message
        return emptyList()
    }

    private fun fail(pkgId: String, error: Throwable) {
        val root = generateSequence(error) { it.cause }.last()
        val message = listOfNotNull(root::class.simpleName, root.message).joinToString(": ")
        Log.e(ExtensionApk.TAG, "Extension load error: $pkgId", error)
        loadErrors[pkgId] = message
    }

    @Suppress("DEPRECATION")
    private fun getInstalledPackagesCompat(pm: PackageManager, flags: Int): List<PackageInfo> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getInstalledPackages(flags)
        }

    @Suppress("DEPRECATION")
    private fun getPackageInfoCompat(pm: PackageManager, pkg: String, flags: Int): PackageInfo =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageInfo(pkg, flags)
        }
}
