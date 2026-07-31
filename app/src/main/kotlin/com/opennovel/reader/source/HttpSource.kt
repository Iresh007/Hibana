package com.opennovel.reader.source

import com.opennovel.reader.source.model.ChapterText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.security.MessageDigest

/**
 * Convenience base for HTTP/HTML sources. Handles the OkHttp fetch + Jsoup
 * parse boilerplate and a deterministic id derived from name/lang/version.
 * Concrete extensions override the CSS-selector-based parsing hooks.
 */
abstract class HttpSource(
    protected val client: OkHttpClient,
) : Source {

    /** Bump when parsing changes so the derived [id] stays stable per version. */
    protected open val versionId: Int = 1

    override val id: Long by lazy { deriveId(name, lang, versionId) }

    protected suspend fun fetchDocument(url: String): Document = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(if (url.startsWith("http")) url else baseUrl.trimEnd('/') + "/" + url.trimStart('/'))
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            Jsoup.parse(response.body!!.string(), request.url.toString())
        }
    }

    /** Default text extraction: pull <p> tags from a content container. */
    protected fun parseParagraphs(doc: Document, contentSelector: String): ChapterText {
        val container = doc.selectFirst(contentSelector) ?: doc.body()
        val paras = container.select("p").map { it.text().trim() }.filter { it.isNotBlank() }
        val fallback = if (paras.isEmpty()) {
            container.wholeText().split("\n").map { it.trim() }.filter { it.isNotBlank() }
        } else paras
        return ChapterText(fallback)
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Android) OpenNovelReader/0.1"

        fun deriveId(name: String, lang: String, version: Int): Long {
            val key = "$name/$lang/$version"
            val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
            var value = 0L
            for (i in 0 until 8) value = value shl 8 or (bytes[i].toLong() and 0xff)
            return value and Long.MAX_VALUE // keep positive
        }
    }
}
