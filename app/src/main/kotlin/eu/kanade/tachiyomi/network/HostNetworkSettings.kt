package eu.kanade.tachiyomi.network

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

/**
 * Live network preferences shared by every HTTP client the app builds (its own and the one
 * extensions use). Values are plain volatiles written from the settings flow, so changing a
 * preference takes effect on the next request without rebuilding any client.
 */
object HostNetworkSettings {

    /** Custom User-Agent, or blank for the built-in default. */
    @Volatile
    var userAgent: String = ""

    /** DNS-over-HTTPS endpoint, or blank for the system resolver. */
    @Volatile
    var dohUrl: String = ""

    private var cachedUrl: String = ""
    private var cachedDoh: DnsOverHttps? = null

    private fun dohFor(url: String): DnsOverHttps? = synchronized(this) {
        if (url.isBlank()) return null
        if (url != cachedUrl) {
            cachedDoh = runCatching {
                DnsOverHttps.Builder()
                    .client(OkHttpClient())
                    .url(url.toHttpUrl())
                    .includeIPv6(true)
                    .build()
            }.getOrNull()
            cachedUrl = url
        }
        cachedDoh
    }

    /** Resolves through DoH when configured, falling back to the system resolver on failure. */
    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val doh = dohFor(dohUrl) ?: return Dns.SYSTEM.lookup(hostname)
            return runCatching { doh.lookup(hostname) }.getOrElse { Dns.SYSTEM.lookup(hostname) }
        }
    }
}
