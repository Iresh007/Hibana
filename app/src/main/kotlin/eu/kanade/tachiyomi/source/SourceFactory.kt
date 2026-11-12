// Copied unmodified from mihonapp/mihon v0.21.3 (Apache License 2.0, https://github.com/mihonapp/mihon).
package eu.kanade.tachiyomi.source

/**
 * A factory for creating sources at runtime.
 */
interface SourceFactory {
    /**
     * Create a new copy of the sources
     * @return The created sources
     */
    fun createSources(): List<Source>
}
