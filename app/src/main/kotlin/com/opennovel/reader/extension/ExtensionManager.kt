package com.opennovel.reader.extension

import android.content.Context
import android.util.Log
import com.opennovel.reader.source.SourceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Discovers installed extensions across every [ExtensionLoader] and registers the
 * sources they yield into the [SourceManager]. Discovery and loading are per-
 * extension isolated: one bad package never blocks the rest.
 *
 * Every [loadInstalled] is a full reconciliation: sources this manager registered earlier for an
 * extension that is no longer listed, no longer trusted, or no longer loads are unregistered, so
 * revoking trust (see [revokeAllTrust]) takes effect immediately instead of at the next launch.
 *
 * @param context needed only for [installFromStore]; without it store installs are unavailable.
 */
class ExtensionManager(
    private val loaders: List<ExtensionLoader>,
    private val sourceManager: SourceManager,
    context: Context? = null,
    private val installer: ExtensionInstaller? = context?.let { ExtensionInstaller(it) },
) {
    private val _installed = MutableStateFlow<List<ExtensionInfo>>(emptyList())
    val installed: StateFlow<List<ExtensionInfo>> = _installed.asStateFlow()

    private val _loadErrors = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Per-package reasons an extension is listed but unusable (pkgId -> message). */
    val loadErrors: StateFlow<Map<String, String>> = _loadErrors.asStateFlow()

    private val lock = Mutex()

    /** Source ids this manager registered, by "ecosystem:pkgId". */
    private val registered = HashMap<String, Set<Long>>()

    /** Scan installed extensions and register their runnable sources. Safe to call at startup. */
    suspend fun loadInstalled() = lock.withLock {
        val infos = mutableListOf<ExtensionInfo>()
        val nowRegistered = HashMap<String, Set<Long>>()
        val errors = HashMap<String, String>()

        loaders.forEach { loader ->
            val found = try {
                loader.listInstalled()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(ExtensionApk.TAG, "${loader.ecosystem}: listing installed extensions failed", e)
                emptyList()
            }
            found.forEach { info ->
                var current = info
                if (info.trusted && info.loadError == null) {
                    val sources = try {
                        loader.load(info)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e(ExtensionApk.TAG, "${loader.ecosystem}: loading ${info.pkgId} failed", e)
                        errors[info.pkgId] = e.message ?: e::class.simpleName.orEmpty()
                        emptyList()
                    }
                    sources.forEach(sourceManager::register)
                    if (sources.isNotEmpty()) {
                        nowRegistered["${loader.ecosystem}:${info.pkgId}"] = sources.map { it.id }.toSet()
                    }
                }
                val reported = (loader as? LoadErrorReporter)?.loadErrorFor(info.pkgId)
                val error = errors[info.pkgId] ?: reported ?: info.loadError
                if (error != null) {
                    errors[info.pkgId] = error
                    current = current.copy(loadError = error)
                }
                infos += current
            }
        }

        // Unregister what is no longer loaded (revoked trust, uninstall, load failure).
        val stillRegistered = nowRegistered.values.flatten().toSet()
        registered.values.flatten()
            .filter { it !in stillRegistered }
            .forEach(sourceManager::unregister)
        registered.clear()
        registered.putAll(nowRegistered)

        _loadErrors.value = errors
        _installed.value = infos
    }

    /**
     * Downloads, verifies and installs a store entry as a private extension, then reloads. The
     * extension is *not* trusted automatically; it stays listed as untrusted until approved.
     */
    suspend fun installFromStore(item: RepoExtension): ExtensionInstaller.Result {
        val installer = installer
            ?: return ExtensionInstaller.Result.Failed("Installing is unavailable (no context supplied)")
        val result = installer.install(item)
        if (result is ExtensionInstaller.Result.Installed) loadInstalled()
        return result
    }

    /** Removes a private extension and unloads its sources. */
    suspend fun uninstallPrivate(pkgId: String): Boolean {
        val installer = installer ?: return false
        val removed = installer.uninstallPrivate(pkgId)
        loadInstalled()
        return removed
    }

    /** Mihon's "revoke all unknown extensions": resets every approval and unloads their sources now. */
    suspend fun revokeAllTrust(trustStore: ExtensionTrustStore) {
        trustStore.revokeAll()
        loadInstalled()
    }
}
