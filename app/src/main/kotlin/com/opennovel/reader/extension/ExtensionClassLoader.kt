package com.opennovel.reader.extension

import dalvik.system.PathClassLoader
import java.net.URL
import java.util.Collections
import java.util.Enumeration

/**
 * A [PathClassLoader] with delegate-last semantics, as Mihon's `DelegateLastClassLoaderCompat`
 * (v0.21.3): the boot class loader first (java.*, android.*), then the extension's own dex, then the
 * host loader. Libraries bundled inside an extension therefore win over the host's copies, while the
 * source-api, Injekt, OkHttp... that extensions deliberately do not bundle resolve from the host.
 */
internal class ExtensionClassLoader(
    dexPath: String,
    librarySearchPath: String?,
    parent: ClassLoader,
) : PathClassLoader(dexPath, librarySearchPath, parent) {

    private val bootClassLoader: ClassLoader? = Any::class.java.classLoader

    override fun loadClass(name: String?, resolve: Boolean): Class<*> {
        findLoadedClass(name)?.let { return it }

        if (bootClassLoader != null) {
            try {
                return bootClassLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {
            }
        }

        val fromSelf = try {
            return findClass(name)
        } catch (e: ClassNotFoundException) {
            e
        }

        return try {
            parent.loadClass(name)
        } catch (_: ClassNotFoundException) {
            throw fromSelf
        }
    }

    override fun getResource(name: String?): URL? {
        return bootClassLoader?.getResource(name)
            ?: findResource(name)
            ?: parent?.getResource(name)
    }

    override fun getResources(name: String?): Enumeration<URL> {
        val resources = buildList {
            bootClassLoader?.getResources(name)?.let { addAll(it.toList()) }
            findResources(name)?.let { addAll(it.toList()) }
            parent?.getResources(name)?.let { addAll(it.toList()) }
        }
        return Collections.enumeration(resources)
    }
}
