package com.opennovel.reader.source

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Registry of available sources. Built-in sources are registered at startup;
 * installed extensions (future work) would register here at load time too.
 *
 * This is the seam where a real APK-based extension loader (à la Mihon's
 * ExtensionManager + PackageManager scan) would plug in: it would scan installed
 * packages tagged as OpenNovelReader extensions, load their [Source] factories
 * via a DexClassLoader, and call [register] for each.
 */
class SourceManager {

    private val sourcesById = LinkedHashMap<Long, Source>()
    private val _sources = MutableStateFlow<List<Source>>(emptyList())
    val sources: StateFlow<List<Source>> = _sources.asStateFlow()

    fun register(source: Source) {
        sourcesById[source.id] = source
        _sources.value = sourcesById.values.toList()
    }

    fun registerAll(sources: Iterable<Source>) = sources.forEach(::register)

    fun unregister(id: Long) {
        sourcesById.remove(id)
        _sources.value = sourcesById.values.toList()
    }

    fun get(id: Long): Source? = sourcesById[id]

    fun catalogueSources(): List<Source> = sourcesById.values.toList()
}
