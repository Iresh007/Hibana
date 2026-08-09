package eu.kanade.tachiyomi.network

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cache-control marker recognised by extensions (value ignored by our stack). */
val DEFAULT_CACHE_CONTROL: okhttp3.CacheControl = okhttp3.CacheControl.Builder().build()

fun GET(url: String, headers: Headers = Headers.headersOf(), cache: okhttp3.CacheControl = DEFAULT_CACHE_CONTROL): Request =
    Request.Builder().url(url).headers(headers).cacheControl(cache).build()

fun POST(
    url: String,
    headers: Headers = Headers.headersOf(),
    body: RequestBody = RequestBody.create(null, ByteArray(0)),
    cache: okhttp3.CacheControl = DEFAULT_CACHE_CONTROL,
): Request = Request.Builder().url(url).post(body).headers(headers).cacheControl(cache).build()

/** Suspend bridge over OkHttp's async call, cancellation-aware. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (!response.isSuccessful) {
                cont.resumeWithException(IOException("HTTP error ${response.code}"))
                return
            }
            cont.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isCancelled) return
            cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/**
 * The form modern keiyoushi extensions actually call. Identical to [await] but
 * named to signal the non-2xx throw; omitting it makes those extensions fail to
 * resolve at class-init, so the whole source silently refuses to load.
 */
suspend fun Call.awaitSuccess(): Response {
    val response = await()
    if (!response.isSuccessful) {
        response.close()
        throw IOException("HTTP error ${response.code}")
    }
    return response
}

fun Response.asJsoup(html: String? = null): Document =
    Jsoup.parse(html ?: body!!.string(), request.url.toString())

inline fun <reified T> Response.parseAs(): T =
    jsonHelper.decodeFromString(body!!.string())

@PublishedApi
internal val jsonHelper: Json = Json { ignoreUnknownKeys = true; isLenient = true }

fun String.toFormBody(): RequestBody =
    toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())
