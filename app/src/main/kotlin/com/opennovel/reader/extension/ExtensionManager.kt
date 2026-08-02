package com.opennovel.reader.extension

import com.opennovel.reader.source.SourceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Discovers installed extensions across every [ExtensionLoader] and registers the
 * sources they yield into the [SourceManager]. Discovery and loading are per-
 * extension isolated: one bad package never blocks the rest.
 */
class ExtensionManager(
    private val loaders: List<ExtensionLoader>,
    private val sourceManager: SourceManager,
) {
    private val _installed = MutableStateFlow<List<ExtensionInfo>>(emptyList())
    val installed: StateFlow<List<ExtensionInfo>> = _installed.asStateFlow()

    /** Scan installed extensions and register their runnable sources. Safe to call at startup. */
    suspend fun loadInstalled() {
        val infos = mutableListOf<ExtensionInfo>()
        loaders.forEach { loader ->
            val found = runCatching { loader.listInstalled() }.getOrDefault(emptyList())
            infos += found
            found.forEach { info ->
                runCatching { loader.load(info) }.getOrDefault(emptyList())
                    .forEach(sourceManager::register)
            }
        }
        _installed.value = infos
    }
}
