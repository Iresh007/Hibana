package com.opennovel.reader.source

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * A page of an image chapter as the source reported it. Mihon pages carry two URLs: [url] is the
 * page's own address and [imageUrl] the actual image. When [imageUrl] is null it must be resolved
 * with [ImageChapterSource.resolveImageUrl] before the image can be fetched.
 */
data class ImagePage(
    val index: Int,
    val url: String,
    val imageUrl: String? = null,
)

/**
 * Implemented by sources whose chapters are **images** rather than text — i.e.
 * Mihon/Manatan manga sources.
 *
 * Hibana's [Source] contract returns chapter *text*, which such sources cannot
 * provide directly. Exposing page image URLs separately lets the reader display
 * them and lets text-to-speech OCR them, without forcing every text source to
 * know about pages.
 */
interface ImageChapterSource {
    /**
     * Ordered, fully resolved page image URLs for a chapter. Pages whose image URL is not known up
     * front are resolved one by one (sequentially, to respect source rate limits).
     */
    suspend fun getPageUrls(chapterUrl: String): List<String>

    /** Pages exactly as the source returned them, with unresolved [ImagePage.imageUrl]s left null. */
    suspend fun getPages(chapterUrl: String): List<ImagePage> =
        getPageUrls(chapterUrl).mapIndexed { index, url -> ImagePage(index, url, url) }

    /** Resolves the final image URL of [page] (a network call for sources that need it). */
    suspend fun resolveImageUrl(page: ImagePage): String = page.imageUrl ?: page.url

    /**
     * The source's own OkHttp client. Image hosts often depend on its interceptors (rate limits,
     * descrambling, cookies), so image loaders should use this rather than a generic client.
     */
    val imageClient: OkHttpClient? get() = null

    /** Headers the source sends with image requests (User-Agent, Referer...). Null when none/unknown. */
    val imageHeaders: Headers? get() = null

    /**
     * Fetches the image for [page] exactly as the source would, honouring any `imageRequest`
     * override. The caller owns (and must close) the response. Null when unsupported; fall back to
     * [imageClient] + [imageHeaders] in that case.
     */
    suspend fun openImage(page: ImagePage): Response? = null
}
