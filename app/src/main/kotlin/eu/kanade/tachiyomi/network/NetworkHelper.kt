package eu.kanade.tachiyomi.network

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Host-provided HTTP stack the extensions reach via Injekt (`Injekt.get<NetworkHelper>()`).
 * Extensions read `client` / `cloudflareClient` off this. A process-lifetime cookie
 * jar is kept so login- and challenge-based sources behave across requests.
 */
class NetworkHelper(context: Context) {

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(MemoryCookieJar())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Extensions expect a Cloudflare-aware client; we alias the base client. */
    val cloudflareClient: OkHttpClient = client
}

/** Per-host in-memory cookie store; expired cookies are dropped on read. */
private class MemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val existing = store.getOrPut(host) { mutableListOf() }
        synchronized(existing) {
            cookies.forEach { fresh ->
                existing.removeAll { it.name == fresh.name && it.path == fresh.path }
                existing.add(fresh)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val existing = store[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        return synchronized(existing) {
            existing.removeAll { it.expiresAt < now }
            existing.filter { it.matches(url) }
        }
    }
}
