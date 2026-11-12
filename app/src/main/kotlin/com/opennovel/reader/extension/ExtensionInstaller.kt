package com.opennovel.reader.extension

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Installs extensions offered by a store ([RepoExtension]) as **private** extensions, the way
 * Mihon 0.21.x does: the APK is downloaded, verified, copied to `files/exts/<pkg>.ext` and marked
 * read-only, then loaded from there. No system package installer or user confirmation is involved,
 * and nothing is executed: the extension still has to be approved in [ExtensionTrustStore] before
 * [ApkExtensionLoader] instantiates a single class.
 *
 * Verification before the file is moved into place:
 *  - it parses as an APK for the expected package, declares the `tachiyomi.extension` feature, is not
 *    an anime extension, targets a supported extension-lib, and is signed;
 *  - when the repository publishes a signing-key fingerprint ([RepoExtension.repoSigningKey]) the APK
 *    must be signed with it;
 *  - an update must not downgrade and must be signed with the same certificates as the version it
 *    replaces (private or system-installed).
 */
class ExtensionInstaller(
    context: Context,
    private val clientProvider: () -> OkHttpClient = { ExtensionRuntime.networkHelper().client },
) {
    private val appContext = context.applicationContext

    sealed interface Result {
        data class Installed(val pkgId: String, val versionName: String, val signatureHash: String?) : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun install(item: RepoExtension): Result = withContext(Dispatchers.IO) {
        try {
            doInstall(item)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(ExtensionApk.TAG, "Install failed: ${item.pkgId}", e)
            Result.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    /** Removes a private extension. System-installed ones must be removed through Android. */
    suspend fun uninstallPrivate(pkgId: String): Boolean = withContext(Dispatchers.IO) {
        val file = ExtensionApk.privateFile(appContext, pkgId)
        file.setWritable(true)
        file.delete()
    }

    private fun doInstall(item: RepoExtension): Result {
        ExtensionApk.safeFileName(item.pkgId)
        val url = item.artifact
        if (!url.startsWith("https://")) return Result.Failed("Refusing non-HTTPS download: $url")
        if (item.ecosystem != Ecosystem.MIHON) return Result.Failed("Not an APK extension")

        val downloads = File(appContext.cacheDir, "ext_downloads").apply { mkdirs() }
        val download = File(downloads, "${item.pkgId}.apk")
        download.delete()
        try {
            download(url, download)

            val pkg = ExtensionApk.archiveInfo(appContext, download)
                ?: return Result.Failed("Downloaded file is not a valid APK")
            if (pkg.packageName != item.pkgId) {
                return Result.Failed("Package mismatch: expected ${item.pkgId}, got ${pkg.packageName}")
            }
            if (!ExtensionApk.isExtension(pkg)) return Result.Failed("Not a Tachiyomi/Mihon extension")
            if (ExtensionApk.isAnimeExtension(pkg)) return Result.Failed("Anime extensions are not supported")

            val lib = ExtensionApk.libVersion(pkg)
            if (lib == null || lib !in ExtensionApk.SUPPORTED_LIB_VERSIONS) {
                return Result.Failed("Unsupported extension library version $lib")
            }
            val signatures = ExtensionApk.signatureHashes(pkg)
            if (signatures.isEmpty()) return Result.Failed("The extension is not signed")

            item.repoSigningKey?.takeIf { it.isNotBlank() }?.let { key ->
                if (signatures.none { it.equals(key, ignoreCase = true) }) {
                    return Result.Failed("Not signed with the repository's signing key")
                }
            }

            existingPackage(item.pkgId)?.let { existing ->
                if (PackageInfoCompat.getLongVersionCode(pkg) < PackageInfoCompat.getLongVersionCode(existing)) {
                    return Result.Failed("The installed version is newer, and downgrading isn't allowed")
                }
                if (!signatures.containsAll(ExtensionApk.signatureHashes(existing))) {
                    return Result.Failed("It isn't signed with the same key as the installed extension")
                }
            }

            ExtensionApk.copyToPrivateReadOnly(download, ExtensionApk.privateFile(appContext, item.pkgId))
            return Result.Installed(pkg.packageName, pkg.versionName.orEmpty(), ExtensionApk.trustHash(pkg))
        } finally {
            download.delete()
        }
    }

    private fun existingPackage(pkgId: String) = run {
        val private = ExtensionApk.privateFile(appContext, pkgId).takeIf { it.isFile }
            ?.let { ExtensionApk.archiveInfo(appContext, it) }
        if (private != null) return@run private
        try {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(pkgId, ExtensionApk.PACKAGE_FLAGS)
                .takeIf { ExtensionApk.isExtension(it) }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun download(url: String, target: File) {
        val request = Request.Builder().url(url).build()
        clientProvider().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val body = response.body ?: throw IOException("Empty response")
            if (body.contentLength() > MAX_APK_BYTES) throw IOException("APK is unreasonably large")
            var total = 0L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_APK_BYTES) throw IOException("APK is unreasonably large")
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_APK_BYTES = 150L * 1024 * 1024
    }
}
