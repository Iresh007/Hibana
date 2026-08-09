package com.opennovel.reader.extension.ireader

import android.content.Context
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import ireader.core.http.BrowserEngine
import ireader.core.http.HttpClients
import ireader.core.http.WebViewCookieJar
import ireader.core.http.WebViewManger
import ireader.core.source.Dependencies

/**
 * Builds the real `ireader.core.source.Dependencies` an IReader extension's
 * constructor requires.
 *
 * IReader publishes source-api but not a host implementation, so the app has to
 * assemble the graph itself. Shapes are taken from the published
 * source-api-android 1.5.1 sources:
 *
 *   WebViewManger(context)
 *     -> WebViewCookieJar(cookiesStorage)
 *       -> BrowserEngine(webViewManger, webViewCookieJar)
 *         -> HttpClients(context, browser, cookiesStorage, cookieJar, prefs, webViewManager)
 *
 * The HTTP stack is shared across extensions (one cookie jar and cache keeps
 * Cloudflare/session state coherent), while preferences are namespaced per
 * extension package so one extension cannot read another's settings.
 */
object IReaderDependencyFactory {

    /** Built once and reused; WebView-backed pieces are expensive to duplicate. */
    @Volatile
    private var shared: SharedHttpStack? = null

    private class SharedHttpStack(context: Context, prefsStore: IReaderPreferenceStore) {
        val cookiesStorage = AcceptAllCookiesStorage()
        val webViewManager = WebViewManger(context)
        val webViewCookieJar = WebViewCookieJar(cookiesStorage)
        val browserEngine = BrowserEngine(webViewManager, webViewCookieJar)
        val httpClients = HttpClients(
            context,
            browserEngine,
            cookiesStorage,
            webViewCookieJar,
            prefsStore,
            webViewManager,
        )
    }

    fun create(context: Context, pkgName: String): Dependencies {
        val app = context.applicationContext
        val extensionPrefs = IReaderPreferenceStore(
            app.getSharedPreferences("ireader_ext_$pkgName", Context.MODE_PRIVATE),
        )
        val stack = shared ?: synchronized(this) {
            shared ?: SharedHttpStack(
                app,
                IReaderPreferenceStore(app.getSharedPreferences("ireader_http", Context.MODE_PRIVATE)),
            ).also { shared = it }
        }
        return Dependencies(stack.httpClients, extensionPrefs)
    }
}
