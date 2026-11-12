package com.opennovel.reader.extension

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Registers, in the real Injekt, the host singletons that Mihon/Tachiyomi extensions request.
 *
 * Mihon registers many more types (its AppModule), but extensions can only resolve what the
 * extensions-lib documents: `NetworkHelper` (via `HttpSource.network`), the app `Application`
 * / `Context` (preferences), kotlinx.serialization `Json` (`parseAs`) and `JavaScriptEngine`.
 * Call [init] once, before any extension is loaded.
 *
 * NOTE: the types are registered with explicit `fullType<T>()` references so that
 * `Injekt.get<Context>()` (used by `ConfigurableSource.getSourcePreferences`) and
 * `Injekt.get<Application>()` (used by older extensions) both resolve.
 */
object ExtensionRuntime {

    private val initialized = AtomicBoolean(false)

    @Volatile
    private var network: NetworkHelper? = null

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val app = context.applicationContext as Application

        @Suppress("DEPRECATION")
        val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        AppInfo.init(info?.versionName ?: "0.0.0", info?.versionCode ?: 1)

        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        val helper = NetworkHelper(app)
        network = helper

        Injekt.addSingleton(fullType<Application>(), app)
        Injekt.addSingleton(fullType<Context>(), app)
        Injekt.addSingleton(fullType<Json>(), json)
        Injekt.addSingleton(fullType<NetworkHelper>(), helper)
        Injekt.addSingleton(fullType<JavaScriptEngine>(), JavaScriptEngine(app))
    }

    /** The shared HTTP stack (also used for index/APK downloads). Requires [init]. */
    fun networkHelper(): NetworkHelper =
        network ?: error("ExtensionRuntime.init(context) has not been called")
}
