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
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container built once in [com.opennovel.reader.NovelReaderApp].
 * Keeps a single instance of the database, network client, source manager,
 * repositories, downloader, TTS engine, and extension loaders.
 */
class AppContainer(context: Context) {

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val database = NovelReaderDatabase.get(context)

    val sourceManager = SourceManager()

    val libraryRepository = LibraryRepository(
        novelDao = database.novelDao(),
        chapterDao = database.chapterDao(),
        historyDao = database.historyDao(),
        categoryDao = database.categoryDao(),
        sourceManager = sourceManager,
    )

    val backupManager = com.opennovel.reader.backup.BackupManager(
        novelDao = database.novelDao(),
        chapterDao = database.chapterDao(),
        categoryDao = database.categoryDao(),
        sourceManager = sourceManager,
    )

    val settingsRepository = SettingsRepository(context)

    val downloader = Downloader(
        context = context,
        novelDao = database.novelDao(),
        chapterDao = database.chapterDao(),
        sourceManager = sourceManager,
    )

    val ttsManager = TtsManager(context)

    /** Loaders for each third-party extension ecosystem we interoperate with. */
    val extensionLoaders: List<ExtensionLoader> = listOf(
        IReaderExtensionLoader(context),                 // native IReader novel extensions
        ApkExtensionLoader(context, Ecosystem.MIHON),    // manga (image) extensions
        ApkExtensionLoader(context, Ecosystem.MANATAN),  // manga (image) extensions
        // LNReaderLoader wired once a JsRuntime (QuickJS/Rhino) is provided.
    )

    val extensionManager = com.opennovel.reader.extension.ExtensionManager(extensionLoaders, sourceManager)

    init {
        // Register built-in sources so the app has content on first launch.
        sourceManager.register(GutenbergSource(httpClient))
    }

    /** Scan installed Mihon/Manatan/IReader extensions and register their sources. */
    suspend fun loadInstalledExtensions() = extensionManager.loadInstalled()
}
