package eu.kanade.tachiyomi.network

import android.content.Context
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Host-provided HTTP stack the extensions reach via Injekt (`Injekt.get<NetworkHelper>()`).
 * Extensions read `client` / `cloudflareClient` off this. A shared cookie jar is
 * kept so login-based sources behave.
 */
class NetworkHelper(context: Context) {

    private val cookieManager = java.net.CookieManager().apply {
        setCookiePolicy(java.net.CookiePolicy.ACCEPT_ALL)
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(JavaNetCookieJar(cookieManager))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Extensions expect a Cloudflare-aware client; we alias the base client. */
    val cloudflareClient: OkHttpClient = client
}
