package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Host HTTP stack that extensions resolve through Injekt (`HttpSource.network`). Mirrors the public
 * surface of Mihon v0.21.3's `NetworkHelper` (`client`, `cloudflareClient`, `cookieJar`,
 * `defaultUserAgentProvider()`): cookies are shared with the WebView cookie store, responses are
 * cached on disk (extensions' default `CacheControl` relies on it), and a default User-Agent is set.
 *
 * Not ported: Mihon's WebView-based CloudflareInterceptor, so Cloudflare-challenged sources
 * will still fail.
 */
class NetworkHelper(
    context: Context,
    private val userAgent: () -> String = {
        HostNetworkSettings.userAgent.ifBlank { DEFAULT_USER_AGENT }
    },
) {

    val cookieJar = AndroidCookieJar()

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .dns(HostNetworkSettings.dns)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.MINUTES)
        .cache(
            Cache(
                directory = File(context.cacheDir, "network_cache"),
                maxSize = 5L * 1024 * 1024, // 5 MiB
            ),
        )
        .addInterceptor(UncaughtExceptionInterceptor())
        .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
        .build()

    /**
     * @deprecated Since extension-lib 1.5
     */
    @Deprecated("The regular client handles Cloudflare by default")
    @Suppress("UNUSED")
    val cloudflareClient: OkHttpClient get() = client

    fun defaultUserAgentProvider(): String = userAgent().trim()

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/149.0.0.0 Mobile Safari/537.36"
    }
}
