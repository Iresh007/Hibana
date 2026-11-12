package eu.kanade.tachiyomi

/**
 * Host information used by extensions (e.g. in User-Agent strings). Same JVM shape as Mihon's
 * `eu.kanade.tachiyomi.AppInfo` (object with `getVersionCode/getVersionName/getSupportedImageMimeTypes`);
 * the values are supplied by the host through [init] because this package has no BuildConfig.
 */
@Suppress("UNUSED")
object AppInfo {
    @Volatile private var versionCode: Int = 1
    @Volatile private var versionName: String = "0.0.0"

    fun init(versionName: String, versionCode: Int) {
        this.versionName = versionName
        this.versionCode = versionCode
    }

    /** @since extension-lib 1.3 */
    fun getVersionCode(): Int = versionCode

    /** @since extension-lib 1.3 */
    fun getVersionName(): String = versionName

    /** @since extension-lib 1.5 */
    fun getSupportedImageMimeTypes(): List<String> = listOf(
        "image/avif", "image/gif", "image/heif", "image/jpeg", "image/jxl", "image/png", "image/webp",
    )
}
