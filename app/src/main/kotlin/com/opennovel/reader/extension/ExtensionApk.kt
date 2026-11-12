package com.opennovel.reader.extension

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Package-level knowledge about Mihon/Tachiyomi extension APKs, mirroring Mihon v0.21.3's
 * `ExtensionLoader`:
 *  - an extension declares `<uses-feature android:name="tachiyomi.extension"/>`
 *    (read from [PackageInfo.reqFeatures], which needs `GET_CONFIGURATIONS`);
 *  - application meta-data carries `tachiyomi.extension.class`, `.factory`, `.nsfw`,
 *    `tachiyomix.name`, `tachiyomix.extensionLib` and `tachiyomix.contentWarning`;
 *  - anime extensions use `tachiyomi.animeextension*` and are not supported (this is a reader).
 */
internal object ExtensionApk {
    const val TAG = "HibanaExt"

    const val FEATURE = "tachiyomi.extension"
    const val ANIME_FEATURE = "tachiyomi.animeextension"
    const val META_CLASS = "tachiyomi.extension.class"
    const val META_FACTORY = "tachiyomi.extension.factory"
    const val META_NSFW = "tachiyomi.extension.nsfw"
    const val META_NAME = "tachiyomix.name"
    const val META_LIB = "tachiyomix.extensionLib"
    const val META_CONTENT_WARNING = "tachiyomix.contentWarning"

    /** Same gate as Mihon v0.21.3 (1.5 was dropped by Mihon together with the suspend getMangaDetails API). */
    val SUPPORTED_LIB_VERSIONS = listOf(1.4, 1.6)

    private const val PRIVATE_DIR = "exts"
    private const val PRIVATE_SUFFIX = "ext"

    @Suppress("DEPRECATION")
    val PACKAGE_FLAGS: Int = PackageManager.GET_CONFIGURATIONS or
        PackageManager.GET_META_DATA or
        PackageManager.GET_SIGNATURES or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)

    fun privateDir(context: Context): File = File(context.filesDir, PRIVATE_DIR).apply { mkdirs() }

    fun privateFile(context: Context, pkgName: String): File =
        File(privateDir(context), "${safeFileName(pkgName)}.$PRIVATE_SUFFIX")

    fun isPrivate(context: Context, path: String): Boolean = runCatching {
        File(path).canonicalFile.parentFile == privateDir(context).canonicalFile
    }.getOrDefault(false)

    /** Package names become file names, so keep them to the characters Android allows. */
    fun safeFileName(pkgName: String): String {
        require(pkgName.isNotEmpty() && Regex("[A-Za-z0-9._]+").matches(pkgName) && !pkgName.contains("..")) {
            "Unsafe package name"
        }
        return pkgName
    }

    fun isExtension(pkg: PackageInfo): Boolean =
        pkg.reqFeatures.orEmpty().any { it.name == FEATURE }

    /** True for Manatan/Aniyomi anime extensions, which must not be listed as manga sources. */
    fun isAnimeExtension(pkg: PackageInfo): Boolean =
        pkg.reqFeatures.orEmpty().any { it.name?.startsWith(ANIME_FEATURE) == true } ||
            pkg.packageName.contains(".animeextension.")

    fun archiveInfo(context: Context, file: File): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)?.also {
            it.applicationInfo?.fixBasePaths(file.absolutePath)
        }

    /** On Android 13+ the ApplicationInfo from getPackageArchiveInfo has no sourceDir. */
    private fun ApplicationInfo.fixBasePaths(apkPath: String) {
        if (sourceDir == null) sourceDir = apkPath
        if (publicSourceDir == null) publicSourceDir = apkPath
    }

    /** Extension-lib version (1.4 / 1.6): the float meta-data, else derived from versionName. */
    fun libVersion(pkg: PackageInfo): Double? {
        val meta = pkg.applicationInfo?.metaData
        val declared = meta?.getFloat(META_LIB, 0f)?.takeUnless { it == 0f }?.toString()?.toDoubleOrNull()
            ?: meta?.getString(META_LIB)?.toDoubleOrNull()
        return declared ?: pkg.versionName?.substringBeforeLast('.')?.toDoubleOrNull()
    }

    /** 0 = safe, 1 = mixed, 2 = NSFW. */
    fun contentWarning(pkg: PackageInfo): Int {
        val meta = pkg.applicationInfo?.metaData ?: return 0
        return when {
            meta.containsKey(META_CONTENT_WARNING) -> meta.getInt(META_CONTENT_WARNING).coerceIn(0, 2)
            meta.getInt(META_NSFW) == 1 -> 2
            else -> 0
        }
    }

    fun displayName(pm: PackageManager, pkg: PackageInfo): String =
        pkg.applicationInfo?.metaData?.getString(META_NAME)
            ?: pkg.applicationInfo?.let { pm.getApplicationLabel(it).toString().substringAfter("Tachiyomi: ") }
            ?: pkg.packageName

    /** `eu.kanade.tachiyomi.extension.<lang>.<name>` -> `<lang>`; "all" when it cannot be derived. */
    fun langFromPackage(pkgName: String): String =
        pkgName.substringAfter(".extension.", "").substringBefore('.').ifEmpty { "all" }

    /**
     * SHA-256 hex of every signing certificate. The last entry of a lineage is the current signer
     * (Mihon shows `signatures.last()` as the one to trust).
     */
    fun signatureHashes(pkg: PackageInfo): List<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = pkg.signingInfo
            when {
                signing == null -> null
                signing.hasMultipleSigners() -> signing.apkContentsSigners
                else -> signing.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pkg.signatures
        }
        return signatures.orEmpty().map { sha256Hex(it.toByteArray()) }
    }

    /** The one hash the trust store is keyed on: current signer (last), or a digest of all for multi-signer APKs. */
    fun trustHash(pkg: PackageInfo): String? {
        val all = signatureHashes(pkg)
        return when (all.size) {
            0 -> null
            1 -> all[0]
            else -> {
                val multi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    pkg.signingInfo?.hasMultipleSigners() == true
                if (multi) sha256Hex(all.sorted().joinToString(",").toByteArray()) else all.last()
            }
        }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Copies an APK into private storage and marks it read-only. Android 14+ refuses to load dex
     * from a writable file, and a private copy cannot be swapped by other apps after verification.
     * Written through a `.part` file so a failed copy never leaves a half-written APK behind.
     */
    @Throws(IOException::class)
    fun copyToPrivateReadOnly(source: File, target: File) {
        val staged = File(target.parentFile, "${target.name}.part")
        try {
            staged.delete()
            source.copyTo(staged, overwrite = true)
            staged.setReadOnly()
            // Replacing a read-only target needs it removed first on some filesystems.
            if (target.exists()) {
                target.setWritable(true)
                target.delete()
            }
            if (!staged.renameTo(target)) throw IOException("Unable to replace ${target.name}")
            target.setReadOnly()
        } finally {
            staged.delete()
        }
    }

    fun log(message: String, error: Throwable? = null) {
        if (error != null) Log.e(TAG, message, error) else Log.w(TAG, message)
    }
}
