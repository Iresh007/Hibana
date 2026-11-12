package com.opennovel.reader.source

import okhttp3.Call
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers, per image host, the client and headers the owning source wants for
 * page images. Many image hosts reject requests without the source's Referer or
 * rely on its interceptors (rate limiting, descrambling, cookies), so a generic
 * client gets 403s or garbage. Pages are registered when a chapter's page list is
 * fetched and consumed by [factory].
 */
object SourceImages {

    private class Entry(val client: OkHttpClient?, val headers: Headers?)

    private val byHost = ConcurrentHashMap<String, Entry>()

    /** Records the image client/headers [source] wants for hosts serving [urls]. */
    fun register(source: ImageChapterSource, urls: List<String>) {
        val client = source.imageClient
        val headers = source.imageHeaders
        if (client == null && headers == null) return
        urls.mapNotNull { runCatching { java.net.URI(it).host }.getOrNull() }
            .distinct()
            .forEach { byHost[it] = Entry(client, headers) }
    }

    /** A call factory that applies the registered source client/headers by host. */
    fun factory(fallback: OkHttpClient): Call.Factory = Call.Factory { request ->
        val entry = byHost[request.url.host]
        val client = entry?.client ?: fallback
        val rebuilt = if (entry?.headers != null) {
            val b: Request.Builder = request.newBuilder()
            entry.headers.forEach { (name, value) ->
                // Never override a header the caller already set explicitly.
                if (request.header(name) == null) b.header(name, value)
            }
            b.build()
        } else {
            request
        }
        client.newCall(rebuilt)
    }
}
