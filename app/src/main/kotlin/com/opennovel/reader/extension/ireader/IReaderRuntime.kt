package com.opennovel.reader.extension.ireader

import android.content.Context

/**
 * Host-side capability gate for the IReader ecosystem.
 *
 * Running an IReader extension requires two things on the app classpath that the
 * extension APK does **not** carry:
 *  1. IReader's `source-api` (`ireader.core.source.*`) — the extension's source
 *     class extends `CatalogSource`, so these must resolve from the host.
 *  2. A way to build `ireader.core.source.Dependencies(httpClients, preferences)`,
 *     which needs concrete `HttpClientsInterface` + `PreferenceStore` implementations
 *     (IReader supplies these from its `:core` module).
 *
 * This object is the single wiring point. Once `source-api` (and the two impls)
 * are bundled, set [dependencyFactory] at startup and IReader extensions light up
 * with no further changes to the loader or adapter. Until then [isAvailable] is
 * false and the loader fails fast with a clear message rather than crashing deep
 * inside reflection.
 */
object IReaderRuntime {

    const val DEPENDENCIES_CLASS = "ireader.core.source.Dependencies"
    private const val CATALOG_SOURCE_CLASS = "ireader.core.source.CatalogSource"

    /**
     * Builds an `ireader.core.source.Dependencies`. Wired by the app once the
     * source-api + http/prefs implementations are present. `(context, pkgName) -> Dependencies`.
     */
    @Volatile
    var dependencyFactory: ((Context, String) -> Any?)? = null

    fun isAvailable(): Boolean = apiOnClasspath() && dependencyFactory != null

    fun buildDependencies(context: Context, pkgName: String): Any? =
        dependencyFactory?.invoke(context, pkgName)

    private fun apiOnClasspath(): Boolean = runCatching {
        Class.forName(CATALOG_SOURCE_CLASS, false, this::class.java.classLoader); true
    }.getOrDefault(false)
}
