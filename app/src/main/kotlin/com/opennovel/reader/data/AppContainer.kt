package com.opennovel.reader.data

import android.content.Context
import com.opennovel.reader.data.db.NovelReaderDatabase
import com.opennovel.reader.download.Downloader
import com.opennovel.reader.extension.ApkExtensionLoader
import com.opennovel.reader.extension.Ecosystem
import com.opennovel.reader.extension.ExtensionLoader
import com.opennovel.reader.extension.ireader.IReaderExtensionLoader
import com.opennovel.reader.source.SourceManager
import com.opennovel.reader.source.builtin.GutenbergSource
import com.opennovel.reader.tts.TtsManager
import okhttp3.OkHttpClient
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container built once in [com.opennovel.reader.NovelReaderApp].
 * Keeps a single instance of the database, network client, source manager,
 * repositories, downloader, TTS engine, and extension loaders.
 */
class AppContainer(context: Context) {

    /** Application context, for components that schedule background work. */
    val appContext: Context = context.applicationContext

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val database = NovelReaderDatabase.get(context)

    val sourceManager = SourceManager()

    // Declared before the library repository, which reads incognito mode from it.
    val settingsRepository = SettingsRepository(context)

    val libraryRepository = LibraryRepository(
        novelDao = database.novelDao(),
        chapterDao = database.chapterDao(),
        historyDao = database.historyDao(),
        categoryDao = database.categoryDao(),
        sourceManager = sourceManager,
        incognito = { settingsRepository.settings.first().incognitoMode },
    )

    val backupManager = com.opennovel.reader.backup.BackupManager(
        novelDao = database.novelDao(),
        chapterDao = database.chapterDao(),
        categoryDao = database.categoryDao(),
        sourceManager = sourceManager,
    )

    /** Finds the same work on other sources and moves entries across. */
    val migrationManager = com.opennovel.reader.migration.MigrationManager(
        repo = libraryRepository,
        novelDao = database.novelDao(),
        chapterDao = database.chapterDao(),
        sourceManager = sourceManager,
    )

    val downloader = Downloader(
        context = context,
        novelDao = database.novelDao(),
        chapterDao = database.chapterDao(),
        sourceManager = sourceManager,
    )

    val ttsManager = TtsManager(context)

    /** On-device OCR so TTS can narrate manga pages, which are images. */
    val mangaPageOcr = com.opennovel.reader.tts.MangaPageOcr(httpClient)

    /** On-device translation for OCR'd manga text and novel chapters. */
    val translationManager = com.opennovel.reader.tts.TranslationManager()

    /**
     * Approvals for extension code. Extensions run in this process, so APK-based
     * ones are not loaded until the user trusts their signing certificate.
     */
    val extensionTrustStore = com.opennovel.reader.extension.ExtensionTrustStore(context.applicationContext)

    /** Loaders for each third-party extension ecosystem we interoperate with. */
    val extensionLoaders: List<ExtensionLoader> = listOf(
        IReaderExtensionLoader(context, extensionTrustStore),          // native IReader novel extensions
        ApkExtensionLoader(context, Ecosystem.MIHON, extensionTrustStore),
        ApkExtensionLoader(context, Ecosystem.MANATAN, extensionTrustStore),
        // LNReader JS plugins, run on Rhino (plugins compile to ES5).
        com.opennovel.reader.extension.lnreader.LNReaderPluginLoader(context, httpClient),
    )

    /** User-managed extension repositories and their index parser. */
    val extensionRepoStore = com.opennovel.reader.extension.ExtensionRepoStore(context.applicationContext)
    val repoIndexParser = com.opennovel.reader.extension.RepoIndexParser(httpClient)

    val extensionManager = com.opennovel.reader.extension.ExtensionManager(extensionLoaders, sourceManager)

    init {
        // Provide the host singletons that Tachiyomi/Manatan extensions resolve via
        // Injekt (NetworkHelper + app Context) before any extension is loaded.
        uy.kohesive.injekt.Injekt.addSingleton(eu.kanade.tachiyomi.network.NetworkHelper(context.applicationContext))
        uy.kohesive.injekt.Injekt.addSingleton(context.applicationContext)

        // Supply the IReader Dependencies graph so IReaderExtensionLoader can
        // construct extension sources (isAvailable() gates on this being set).
        com.opennovel.reader.extension.ireader.IReaderRuntime.dependencyFactory = { ctx, pkg ->
            com.opennovel.reader.extension.ireader.IReaderDependencyFactory.create(ctx, pkg)
        }

        // Register built-in sources so the app has content on first launch.
        sourceManager.register(GutenbergSource(httpClient))
    }

    /** Scan installed Mihon/Manatan/IReader extensions and register their sources. */
    suspend fun loadInstalledExtensions() = extensionManager.loadInstalled()
}



