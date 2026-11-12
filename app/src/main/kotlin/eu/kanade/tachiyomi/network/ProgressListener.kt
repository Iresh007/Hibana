// Copied unmodified from mihonapp/mihon v0.21.3 (Apache License 2.0, https://github.com/mihonapp/mihon).
package eu.kanade.tachiyomi.network

interface ProgressListener {
    fun update(bytesRead: Long, contentLength: Long, done: Boolean)
}
