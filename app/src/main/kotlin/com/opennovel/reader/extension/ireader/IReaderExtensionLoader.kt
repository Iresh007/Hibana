package com.opennovel.reader.extension.ireader

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.opennovel.reader.extension.Ecosystem
import com.opennovel.reader.extension.ExtensionInfo
import com.opennovel.reader.extension.ExtensionLoader
import com.opennovel.reader.source.Source
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Loads **IReader** extensions natively — a faithful port of IReader's own
 * `AndroidCatalogLoader` (data/androidMain), reduced to the novel path.
 *
 * IReader packages an extension as an APK that:
 *  - declares the required-feature `ireader` (`<uses-feature android:name="ireader"/>`),
 *  - carries `<meta-data android:name="source.class" .../>` naming the source class,
 *  - is compiled against `ireader.core.source.*` with the API `compileOnly`, i.e.
 *    the API classes are provided by the **host**, not the APK.
 *
 * Loading steps (matching IReader exactly):
 *  1. Scan installed packages for the `ireader` feature (`GET_META_DATA | GET_CONFIGURATIONS`).
 *  2. Validate the lib major version (IReader 2.0.x accepts major == 2).
 *  3. Copy the APK to `codeCacheDir`, make it read-only (Android 15 requirement),
 *     and open a `DexClassLoader` with the app classloader as parent.
 *  4. `Class.forName(source.class).getConstructor(Dependencies).newInstance(deps)`.
 *  5. Wrap the returned `ireader.core.source.CatalogSource` in [IReaderSourceAdapter].
 *
 * HOST REQUIREMENT (documented, enforced at runtime): because the source class
 * extends `ireader.core.source.CatalogSource`, those API classes must be present
 * on the app classpath. [isRuntimeAvailable] checks for them; until IReader's
 * `source-api` is bundled into the app, [load] reports a clear, actionable error
 * instead of crashing. See README → "IReader extension support".
 */
class IReaderExtensionLoader(
    private val context: Context,
) : ExtensionLoader {

    override val ecosystem = Ecosystem.IREADER

    private val pm: PackageManager get() = context.packageManager

    override suspend fun listAvailable(repoUrl: String): List<ExtensionInfo> = emptyList()

    override suspend fun listInstalled(): List<ExtensionInfo> = withContext(Dispatchers.IO) {
        installedExtensionPackages().mapNotNull { pkg ->
            val meta = pkg.applicationInfo?.metaData ?: return@mapNotNull null
            val sourceClass = meta.getString(METADATA_SOURCE_CLASS)?.trim() ?: return@mapNotNull null
            if (!isSupportedVersion(pkg.versionName)) return@mapNotNull null
            ExtensionInfo(
                pkgId = pkg.packageName,
                name = pkg.applicationInfo!!.loadLabel(pm).toString(),
                lang = meta.getString(METADATA_LANG, "all"),
                versionName = pkg.versionName ?: "?",
                ecosystem = Ecosystem.IREADER,
                installed = true,
                artifact = pkg.applicationInfo!!.sourceDir,
            ).also { it.attach(sourceClass, meta.getString(METADATA_DESCRIPTION).orEmpty()) }
        }
    }

    override suspend fun load(info: ExtensionInfo): List<Source> = withContext(Dispatchers.IO) {
        if (!IReaderRuntime.isAvailable()) {
            throw IReaderRuntimeUnavailable(
                "IReader extension '${info.name}' cannot run: the host app does not yet " +
                    "bundle IReader's source-api (ireader.core.source.*). Add the source-api " +
                    "module to enable native IReader extensions. See README.",
            )
        }
        val pkg = pm.getPackageInfoCompat(info.pkgId)
        val meta = pkg.applicationInfo?.metaData ?: return@withContext emptyList()
        val sourceClass = meta.getString(METADATA_SOURCE_CLASS)?.trim() ?: return@withContext emptyList()
        val resolvedClass = if (sourceClass.startsWith(".")) info.pkgId + sourceClass else sourceClass

        val loader = createClassLoader(File(info.artifact), info.pkgId)
        val deps = IReaderRuntime.buildDependencies(context, info.pkgId)
            ?: throw IReaderRuntimeUnavailable("Could not construct ireader Dependencies")

        val sourceObj = instantiateSource(resolvedClass, loader, deps)
            ?: return@withContext emptyList()

        listOf(IReaderSourceAdapter(sourceObj, loader, info))
    }

    /** Reflectively `Class.forName(...).getConstructor(Dependencies).newInstance(deps)`. */
    private fun instantiateSource(className: String, loader: ClassLoader, deps: Any): Any? = runCatching {
        val clazz = Class.forName(className, false, loader)
        val depsClass = loader.loadClass(IReaderRuntime.DEPENDENCIES_CLASS)
        val ctor = clazz.getConstructor(depsClass)
        ctor.newInstance(deps)
    }.getOrNull()

    /** Android 15 rejects writable dex; copy to codeCacheDir read-only before loading. */
    private fun createClassLoader(apk: File, pkgName: String): ClassLoader {
        val cacheDir = File(context.codeCacheDir, "ireader_apk").apply { mkdirs() }
        val readOnly = File(cacheDir, "$pkgName.apk")
        if (!readOnly.exists() || readOnly.length() != apk.length()) {
            apk.copyTo(readOnly, overwrite = true)
            readOnly.setReadOnly()
        }
        val dexOut = File(context.codeCacheDir, "ireader_dex/${pkgName}_${System.currentTimeMillis()}")
            .apply { mkdirs() }
        return DexClassLoader(readOnly.absolutePath, dexOut.absolutePath, null, context.classLoader)
    }

    private fun installedExtensionPackages(): List<PackageInfo> = runCatching {
        pm.getInstalledPackagesCompat(PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS)
            .filter { pkg -> pkg.reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE } }
    }.getOrDefault(emptyList())

    private fun isSupportedVersion(versionName: String?): Boolean {
        val major = versionName?.substringBefore('.')?.toIntOrNull() ?: return false
        return major in LIB_VERSION_MIN..LIB_VERSION_MAX
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.getInstalledPackagesCompat(flags: Int): List<PackageInfo> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            getInstalledPackages(flags)
        }

    @Suppress("DEPRECATION")
    private fun PackageManager.getPackageInfoCompat(pkg: String): PackageInfo {
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            getPackageInfo(pkg, flags)
        }
    }

    private companion object {
        // Matches ireader.data.catalog.impl.AndroidCatalogLoader constants.
        const val EXTENSION_FEATURE = "ireader"
        const val METADATA_SOURCE_CLASS = "source.class"
        const val METADATA_DESCRIPTION = "source.description"
        const val METADATA_LANG = "source.lang"
        const val LIB_VERSION_MIN = 2
        const val LIB_VERSION_MAX = 2
    }
}

class IReaderRuntimeUnavailable(message: String) : Exception(message)

/** Carries IReader-specific metadata alongside the generic [ExtensionInfo]. */
private val extraStore = HashMap<String, Pair<String, String>>()
fun ExtensionInfo.attach(sourceClass: String, description: String) {
    extraStore[pkgId] = sourceClass to description
}
fun ExtensionInfo.sourceClass(): String? = extraStore[pkgId]?.first
