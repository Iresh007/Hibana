package com.opennovel.reader.extension

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

private val Context.trustStore by preferencesDataStore(name = "extension_trust")

/**
 * Tracks which extensions the user has explicitly trusted, as Mihon does.
 *
 * Extensions are arbitrary code loaded into this process, so they are not run
 * until the user approves them. Trust is keyed on **package + signing
 * certificate**, not package alone: that way an update re-signed by a different
 * party loses trust automatically and has to be approved again, which is exactly
 * the case worth catching — a hijacked or impersonated extension.
 */
class ExtensionTrustStore(private val context: Context) {

    /** Entries are "pkgId:signatureSha256". */
    val trusted: Flow<Set<String>> = context.trustStore.data.map { it[TRUSTED] ?: emptySet() }

    suspend fun isTrusted(pkgId: String, signatureHash: String): Boolean =
        key(pkgId, signatureHash) in trusted.first()

    suspend fun trust(pkgId: String, signatureHash: String) {
        context.trustStore.edit { prefs ->
            prefs[TRUSTED] = (prefs[TRUSTED] ?: emptySet()) + key(pkgId, signatureHash)
        }
    }

    /** Revokes every signature recorded for a package. */
    suspend fun untrust(pkgId: String) {
        context.trustStore.edit { prefs ->
            prefs[TRUSTED] = (prefs[TRUSTED] ?: emptySet()).filterNot { it.startsWith("$pkgId:") }.toSet()
        }
    }

    /** Mihon's "revoke all unknown extensions" — resets every approval. */
    suspend fun revokeAll() {
        context.trustStore.edit { it[TRUSTED] = emptySet() }
    }

    private fun key(pkgId: String, hash: String) = "$pkgId:$hash"

    private companion object {
        val TRUSTED = stringSetPreferencesKey("trusted_signatures")
    }
}

/**
 * SHA-256 of a package's signing certificate, or null when it can't be read.
 *
 * Uses the modern signing-info API where available and falls back to the
 * deprecated one below API 28, since both are still in play at our minSdk.
 */
@Suppress("DEPRECATION", "PackageManagerGetSignatures")
fun PackageManager.signatureHashOf(pkgId: String): String? = runCatching {
    val info: PackageInfo
    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info = getPackageInfo(pkgId, PackageManager.GET_SIGNING_CERTIFICATES)
        info.signingInfo?.let {
            if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
        }
    } else {
        info = getPackageInfo(pkgId, PackageManager.GET_SIGNATURES)
        info.signatures
    } ?: return null

    val first = signatures.firstOrNull() ?: return null
    MessageDigest.getInstance("SHA-256")
        .digest(first.toByteArray())
        .joinToString("") { "%02x".format(it) }
}.getOrNull()
