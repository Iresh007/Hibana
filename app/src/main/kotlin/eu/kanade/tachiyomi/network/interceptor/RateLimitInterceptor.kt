package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Host implementation of the extensions-lib rate limiters. Extensions build their
 * client with `.rateLimit(3)` / `.rateLimitHost(url, 1)` at class-init time, so a
 * missing symbol here stops the source loading at all rather than degrading.
 *
 * Sliding window: at most [permits] requests per [period]. Callers block until a
 * slot frees, which is what the real lib does and what sources rely on to avoid
 * being banned.
 */
private class RateLimitInterceptor(
    private val permits: Int,
    private val period: Long,
    private val unit: TimeUnit,
    private val hostMatcher: ((HttpUrl) -> Boolean)? = null,
) : Interceptor {

    private val periodMillis = unit.toMillis(period)
    private val timestamps = ArrayDeque<Long>(permits)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (hostMatcher?.invoke(request.url) == false) return chain.proceed(request)

        synchronized(timestamps) {
            while (true) {
                val now = System.currentTimeMillis()
                while (timestamps.isNotEmpty() && now - timestamps.first() >= periodMillis) {
                    timestamps.removeFirst()
                }
                if (timestamps.size < permits) {
                    timestamps.addLast(now)
                    break
                }
                val waitFor = periodMillis - (now - timestamps.first())
                if (waitFor > 0) {
                    try {
                        Thread.sleep(waitFor)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw java.io.InterruptedIOException("Interrupted while rate limiting")
                    }
                }
            }
        }
        return chain.proceed(request)
    }
}

fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder = addInterceptor(RateLimitInterceptor(permits, period, unit))

fun OkHttpClient.Builder.rateLimitHost(
    httpUrl: HttpUrl,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder = addInterceptor(
    RateLimitInterceptor(permits, period, unit) { it.host == httpUrl.host },
)

fun OkHttpClient.Builder.rateLimitHost(
    url: String,
    permits: Int,
    period: Long = 1,
    unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder = addInterceptor(
    RateLimitInterceptor(permits, period, unit) { candidate ->
        url.toHttpUrlOrNull()?.host == candidate.host
    },
)
