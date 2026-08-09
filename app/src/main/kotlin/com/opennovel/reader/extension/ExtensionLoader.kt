package com.opennovel.reader.extension

import com.opennovel.reader.source.Source

/**
 * Describes an installed or installable extension, independent of which
 * ecosystem it came from. The [ExtensionLoader]s scan their respective package
 * formats and yield these so the rest of the app treats every source uniformly.
 */
data class ExtensionInfo(
    val pkgId: String,
    val name: String,
    val lang: String,
    val versionName: String,
    val ecosystem: Ecosystem,
    val installed: Boolean,
    /** Path/URL to the extension artifact (APK, .js bundle, repo entry). */
    val artifact: String,
    /**
     * SHA-256 of the APK signing certificate, or null for non-APK ecosystems.
     * Trust is keyed on this so a re-signed update must be re-approved.
     */
    val signatureHash: String? = null,
    /**
     * Whether the user has approved running this extension's code. APK
     * extensions start untrusted; JS plugins are sandboxed in the JS runtime and
     * are installed deliberately by the user, so they don't need this gate.
     */
    val trusted: Boolean = true,
)

/**
 * The extension ecosystems this app aims to interoperate with. Each has its own
 * packaging, language, and API surface, so each needs a dedicated adapter that
 * maps its native source objects onto our [Source] contract.
 */
enum class Ecosystem(val label: String) {
    /** Mihon / Tachiyomi APK extensions (Kotlin, `eu.kanade.tachiyomi.source.*`). Manga-oriented. */
    MIHON("Mihon / Tachiyomi"),

    /** IReader Kotlin extensions (`ireader-*`, novel-oriented). */
    IREADER("IReader"),

    /** LNReader JavaScript plugins (React-Native app, `.js` plugins from a repo). */
    LNREADER("LNReader"),

    /** Manatan / manga-app style APK extensions. */
    MANATAN("Manatan"),

    /** Sources shipped inside this app. */
    BUILTIN("Built-in"),
}

/**
 * Loads extensions from one ecosystem into runnable [Source]s.
 *
 * Every ecosystem needs its own implementation because the packaging and
 * runtime differ fundamentally:
 *  - APK-based (Mihon/IReader/Manatan): scan installed packages, load classes
 *    with a DexClassLoader, instantiate the ecosystem's Source, wrap in an adapter.
 *  - JS-based (LNReader): fetch the plugin repo, download `.js` plugins, run them
 *    in an embedded JS engine, bridge fetch()/parsing to native.
 */
interface ExtensionLoader {
    val ecosystem: Ecosystem

    /** Discover extensions available from a repository URL. */
    suspend fun listAvailable(repoUrl: String): List<ExtensionInfo>

    /** Discover extensions already installed/loadable on-device. */
    suspend fun listInstalled(): List<ExtensionInfo>

    /** Materialize an installed extension into runnable sources. */
    suspend fun load(info: ExtensionInfo): List<Source>
}
