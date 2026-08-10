package com.opennovel.reader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Reader + TTS preferences persisted with DataStore. */
data class ReaderSettings(
    val fontScale: Float = 1.0f,
    val lineSpacing: Float = 1.4f,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val fontFamily: String = "serif",
    val ttsSpeed: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsVoice: String = "",
    val keepScreenOn: Boolean = true,
    /** How manga/comic pages are laid out and paged. */
    val readingMode: ReadingMode = ReadingMode.WEBTOON,
    /** Script used when OCR'ing manga pages for narration/translation. */
    val ocrScript: OcrScriptSetting = OcrScriptSetting.LATIN,
    /** Language the narrator speaks in. */
    val ttsLanguage: SpeechLanguage = SpeechLanguage.ENGLISH,
    /** Translate recognised/【novel】text before displaying or speaking it. */
    val translateEnabled: Boolean = false,
    val translateTarget: TranslateLanguage = TranslateLanguage.ENGLISH,
    /** How often the library checks sources for new chapters. */
    val updateSchedule: UpdateSchedule = UpdateSchedule.MANUAL,
    /** Hour of day (0-23) for schedules that run at a fixed time. */
    val updateHour: Int = 3,
    val updateMinute: Int = 0,
    /** Day of week (1=Mon..7=Sun) for [UpdateSchedule.WEEKLY]. */
    val updateDayOfWeek: Int = 1,
    /** Day of month (1-28) for [UpdateSchedule.MONTHLY]. */
    val updateDayOfMonth: Int = 1,
    /** Only run scheduled updates on unmetered networks. */
    val updateOnWifiOnly: Boolean = true,
    /** How library entries are laid out. */
    val libraryDisplayMode: LibraryDisplayMode = LibraryDisplayMode.COMFORTABLE_GRID,
    /** Show unread / downloaded counts on covers. */
    val showLibraryBadges: Boolean = true,

    // --- appearance ---
    /**
     * Material You. Off by default so the Hibana brand palette survives on
     * Android 12+; [com.opennovel.reader.ui.theme.OpenNovelTheme] defaults the
     * same way.
     */
    val dynamicColor: Boolean = false,
    /** BCP-47 tag, or empty to follow the system locale. */
    val appLanguage: String = "",

    // --- library ---
    /**
     * Category new entries land in. [NO_DEFAULT_CATEGORY] means "ask each time",
     * which is distinct from the uncategorised Default shelf (id 0).
     */
    val defaultCategoryId: Long = NO_DEFAULT_CATEGORY,
    val showUnreadBadge: Boolean = true,
    val showDownloadedBadge: Boolean = true,
    val showLanguageBadge: Boolean = false,

    // --- comic reader ---
    val comicPageLayout: PageLayout = PageLayout.SINGLE,
    val comicFullscreen: Boolean = true,
    /** Where a tap turns the page. Reader-wide: it is muscle memory, not per-title. */
    val tapZoneLayout: TapZoneLayout = TapZoneLayout.L_SHAPED,
    /**
     * Page with the volume rocker. Off by default because swallowing the keys
     * takes volume control away from whatever else is playing.
     */
    val volumeKeyPaging: Boolean = false,

    // --- downloads ---
    /** Display-only path of the download root; empty means app-private storage. */
    val downloadLocation: String = "",
    val downloadNewChapters: Boolean = false,
    val removeAfterRead: Boolean = false,
    val concurrentDownloads: Int = 2,

    // --- browse ---
    val includeNsfwSources: Boolean = true,
    val autoUpdateExtensions: Boolean = true,

    // --- data & storage ---
    val autoBackupFrequency: AutoBackupFrequency = AutoBackupFrequency.OFF,

    // --- privacy ---
    val appLockEnabled: Boolean = false,
    /** Sets FLAG_SECURE, which blocks screenshots and the recents thumbnail. */
    val secureScreen: Boolean = false,
    /** Suppresses history and progress writes while reading. */
    val incognitoMode: Boolean = false,
)

/** Sentinel for "no default category — ask when adding". */
const val NO_DEFAULT_CATEGORY = -1L

/** How many pages a comic reader shows at once. */
enum class PageLayout(val label: String) {
    SINGLE("Single page"),
    DOUBLE("Double page"),
    /** Double pages, except the first — keeps covers on their own. */
    DOUBLE_EXCEPT_COVER("Double page (cover alone)"),
}

enum class AutoBackupFrequency(val label: String) {
    OFF("Off"),
    DAILY("Daily"),
    EVERY_2_DAYS("Every 2 days"),
    WEEKLY("Weekly"),
}

/** Library layout, mirroring the options Mihon offers. */
enum class LibraryDisplayMode(val label: String) {
    COMFORTABLE_GRID("Comfortable grid"),
    COMPACT_GRID("Compact grid"),
    LIST("List"),
}

/**
 * Library update cadence. WorkManager enforces a 15-minute floor on periodic
 * work, so every interval here is comfortably above it. Schedules with a fixed
 * time are implemented as one-shot work that reschedules itself, since periodic
 * work cannot guarantee a wall-clock time.
 */
enum class UpdateSchedule(val label: String) {
    MANUAL("Only when I refresh"),
    EVERY_6_HOURS("Every 6 hours"),
    EVERY_12_HOURS("Every 12 hours"),
    DAILY("Every 24 hours"),
    ALTERNATE_DAY("Every other day"),
    WEEKLY("Weekly (pick day and time)"),
    MONTHLY("Monthly (pick date and time)"),
}

enum class ThemeMode { LIGHT, DARK, SYSTEM, SEPIA, BLACK }

/**
 * Page layout for image chapters.
 *  - [WEBTOON]/[CONTINUOUS_VERTICAL]: scrolling strips, for manhwa/manhua.
 *  - [PAGED_LTR]/[PAGED_RTL]: one page at a time; RTL is the Japanese manga convention.
 *  - [PAGED_VERTICAL]: e-reader style, tap/swipe up-down through discrete pages.
 */
enum class ReadingMode(val label: String) {
    WEBTOON("Webtoon (continuous)"),
    CONTINUOUS_VERTICAL("Vertical strip (gapped)"),
    PAGED_LTR("Paged — left to right"),
    PAGED_RTL("Paged — right to left"),
    PAGED_VERTICAL("Paged — vertical (e-reader)"),
}

/**
 * Which part of the screen turns the page when tapped.
 *
 * The shapes are the ones readers already know from Mihon/Tachiyomi; the centre
 * of every layout opens the reader menu, and [DISABLED] makes the whole screen
 * do that so a tap can never lose someone's place.
 */
enum class TapZoneLayout(val label: String) {
    L_SHAPED("L-shaped"),
    KINDLE("Kindle-style"),
    EDGE("Edge"),
    DISABLED("Disabled"),
}

/** OCR model to use; manga lettering differs enough per script to matter. */
enum class OcrScriptSetting(val label: String) {
    LATIN("Latin / English"),
    JAPANESE("Japanese"),
    KOREAN("Korean"),
    CHINESE("Chinese"),
}

enum class SpeechLanguage(val label: String, val tag: String) {
    ENGLISH("English", "en"),
    HINDI("Hindi", "hi"),
}

enum class TranslateLanguage(val label: String, val code: String) {
    ENGLISH("English", "en"),
    HINDI("Hindi", "hi"),
}

class SettingsRepository(private val context: Context) {

    val settings: Flow<ReaderSettings> = context.dataStore.data.map { p ->
        ReaderSettings(
            fontScale = p[FONT_SCALE] ?: 1.0f,
            lineSpacing = p[LINE_SPACING] ?: 1.4f,
            themeMode = ThemeMode.valueOf(p[THEME_MODE] ?: ThemeMode.DARK.name),
            fontFamily = p[FONT_FAMILY] ?: "serif",
            ttsSpeed = p[TTS_SPEED] ?: 1.0f,
            ttsPitch = p[TTS_PITCH] ?: 1.0f,
            ttsVoice = p[TTS_VOICE] ?: "",
            keepScreenOn = p[KEEP_SCREEN_ON] ?: true,
            readingMode = enumOr(p[READING_MODE], ReadingMode.WEBTOON),
            ocrScript = enumOr(p[OCR_SCRIPT], OcrScriptSetting.LATIN),
            ttsLanguage = enumOr(p[TTS_LANGUAGE], SpeechLanguage.ENGLISH),
            translateEnabled = p[TRANSLATE_ENABLED] ?: false,
            translateTarget = enumOr(p[TRANSLATE_TARGET], TranslateLanguage.ENGLISH),
            updateSchedule = enumOr(p[UPDATE_SCHEDULE], UpdateSchedule.MANUAL),
            updateHour = p[UPDATE_HOUR] ?: 3,
            updateMinute = p[UPDATE_MINUTE] ?: 0,
            updateDayOfWeek = p[UPDATE_DOW] ?: 1,
            updateDayOfMonth = p[UPDATE_DOM] ?: 1,
            updateOnWifiOnly = p[UPDATE_WIFI_ONLY] ?: true,
            libraryDisplayMode = enumOr(p[LIBRARY_DISPLAY], LibraryDisplayMode.COMFORTABLE_GRID),
            showLibraryBadges = p[LIBRARY_BADGES] ?: true,
            dynamicColor = p[DYNAMIC_COLOR] ?: false,
            appLanguage = p[APP_LANGUAGE] ?: "",
            defaultCategoryId = p[DEFAULT_CATEGORY] ?: NO_DEFAULT_CATEGORY,
            showUnreadBadge = p[BADGE_UNREAD] ?: true,
            showDownloadedBadge = p[BADGE_DOWNLOADED] ?: true,
            showLanguageBadge = p[BADGE_LANGUAGE] ?: false,
            comicPageLayout = enumOr(p[COMIC_PAGE_LAYOUT], PageLayout.SINGLE),
            comicFullscreen = p[COMIC_FULLSCREEN] ?: true,
            tapZoneLayout = enumOr(p[TAP_ZONE_LAYOUT], TapZoneLayout.L_SHAPED),
            volumeKeyPaging = p[VOLUME_KEY_PAGING] ?: false,
            downloadLocation = p[DOWNLOAD_LOCATION] ?: "",
            downloadNewChapters = p[DOWNLOAD_NEW_CHAPTERS] ?: false,
            removeAfterRead = p[REMOVE_AFTER_READ] ?: false,
            concurrentDownloads = p[CONCURRENT_DOWNLOADS] ?: 2,
            includeNsfwSources = p[INCLUDE_NSFW] ?: true,
            autoUpdateExtensions = p[AUTO_UPDATE_EXTENSIONS] ?: true,
            autoBackupFrequency = enumOr(p[AUTO_BACKUP], AutoBackupFrequency.OFF),
            appLockEnabled = p[APP_LOCK] ?: false,
            secureScreen = p[SECURE_SCREEN] ?: false,
            incognitoMode = p[INCOGNITO] ?: false,
        )
    }

    /** Tolerates stored values from older builds instead of crashing on rename. */
    private inline fun <reified T : Enum<T>> enumOr(value: String?, fallback: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    /**
     * When the library was last swept for new chapters. Kept out of
     * [ReaderSettings] deliberately: it changes on every refresh, and folding it
     * into that object would re-emit the whole settings flow — and so recompose
     * every screen observing any preference — each time a sweep finishes.
     */
    val lastLibraryRefresh: Flow<Long> = context.dataStore.data.map { it[LAST_REFRESH] ?: 0L }

    suspend fun setLastLibraryRefresh(millis: Long) = edit { it[LAST_REFRESH] = millis }

    suspend fun setFontScale(v: Float) = edit { it[FONT_SCALE] = v }
    suspend fun setLineSpacing(v: Float) = edit { it[LINE_SPACING] = v }
    suspend fun setThemeMode(v: ThemeMode) = edit { it[THEME_MODE] = v.name }
    suspend fun setFontFamily(v: String) = edit { it[FONT_FAMILY] = v }
    suspend fun setTtsSpeed(v: Float) = edit { it[TTS_SPEED] = v }
    suspend fun setTtsPitch(v: Float) = edit { it[TTS_PITCH] = v }
    suspend fun setTtsVoice(v: String) = edit { it[TTS_VOICE] = v }
    suspend fun setKeepScreenOn(v: Boolean) = edit { it[KEEP_SCREEN_ON] = v }
    suspend fun setReadingMode(v: ReadingMode) = edit { it[READING_MODE] = v.name }
    suspend fun setOcrScript(v: OcrScriptSetting) = edit { it[OCR_SCRIPT] = v.name }
    suspend fun setTtsLanguage(v: SpeechLanguage) = edit { it[TTS_LANGUAGE] = v.name }
    suspend fun setTranslateEnabled(v: Boolean) = edit { it[TRANSLATE_ENABLED] = v }
    suspend fun setTranslateTarget(v: TranslateLanguage) = edit { it[TRANSLATE_TARGET] = v.name }
    suspend fun setUpdateSchedule(v: UpdateSchedule) = edit { it[UPDATE_SCHEDULE] = v.name }
    suspend fun setUpdateTime(hour: Int, minute: Int) = edit {
        it[UPDATE_HOUR] = hour.coerceIn(0, 23); it[UPDATE_MINUTE] = minute.coerceIn(0, 59)
    }
    suspend fun setUpdateDayOfWeek(v: Int) = edit { it[UPDATE_DOW] = v.coerceIn(1, 7) }
    // Capped at 28 so every month has the date.
    suspend fun setUpdateDayOfMonth(v: Int) = edit { it[UPDATE_DOM] = v.coerceIn(1, 28) }
    suspend fun setUpdateOnWifiOnly(v: Boolean) = edit { it[UPDATE_WIFI_ONLY] = v }
    suspend fun setLibraryDisplayMode(v: LibraryDisplayMode) = edit { it[LIBRARY_DISPLAY] = v.name }
    suspend fun setShowLibraryBadges(v: Boolean) = edit { it[LIBRARY_BADGES] = v }
    suspend fun setDynamicColor(v: Boolean) = edit { it[DYNAMIC_COLOR] = v }
    suspend fun setAppLanguage(v: String) = edit { it[APP_LANGUAGE] = v }
    suspend fun setDefaultCategoryId(v: Long) = edit { it[DEFAULT_CATEGORY] = v }
    suspend fun setShowUnreadBadge(v: Boolean) = edit { it[BADGE_UNREAD] = v }
    suspend fun setShowDownloadedBadge(v: Boolean) = edit { it[BADGE_DOWNLOADED] = v }
    suspend fun setShowLanguageBadge(v: Boolean) = edit { it[BADGE_LANGUAGE] = v }
    suspend fun setComicPageLayout(v: PageLayout) = edit { it[COMIC_PAGE_LAYOUT] = v.name }
    suspend fun setComicFullscreen(v: Boolean) = edit { it[COMIC_FULLSCREEN] = v }
    suspend fun setTapZoneLayout(v: TapZoneLayout) = edit { it[TAP_ZONE_LAYOUT] = v.name }
    suspend fun setVolumeKeyPaging(v: Boolean) = edit { it[VOLUME_KEY_PAGING] = v }
    suspend fun setDownloadLocation(v: String) = edit { it[DOWNLOAD_LOCATION] = v }
    suspend fun setDownloadNewChapters(v: Boolean) = edit { it[DOWNLOAD_NEW_CHAPTERS] = v }
    suspend fun setRemoveAfterRead(v: Boolean) = edit { it[REMOVE_AFTER_READ] = v }
    // Above ~5 parallel fetches most sources start rate-limiting or banning.
    suspend fun setConcurrentDownloads(v: Int) = edit { it[CONCURRENT_DOWNLOADS] = v.coerceIn(1, 5) }
    suspend fun setIncludeNsfwSources(v: Boolean) = edit { it[INCLUDE_NSFW] = v }
    suspend fun setAutoUpdateExtensions(v: Boolean) = edit { it[AUTO_UPDATE_EXTENSIONS] = v }
    suspend fun setAutoBackupFrequency(v: AutoBackupFrequency) = edit { it[AUTO_BACKUP] = v.name }
    suspend fun setAppLockEnabled(v: Boolean) = edit { it[APP_LOCK] = v }
    suspend fun setSecureScreen(v: Boolean) = edit { it[SECURE_SCREEN] = v }
    suspend fun setIncognitoMode(v: Boolean) = edit { it[INCOGNITO] = v }

    /**
     * Wipes every stored preference so the next read falls back to the defaults
     * in [ReaderSettings]. [LAST_REFRESH] goes with it; it is derived state that
     * the next library sweep rewrites anyway.
     */
    suspend fun resetAll() = edit { it.clear() }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private companion object {
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val LINE_SPACING = floatPreferencesKey("line_spacing")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val FONT_FAMILY = stringPreferencesKey("font_family")
        val TTS_SPEED = floatPreferencesKey("tts_speed")
        val TTS_PITCH = floatPreferencesKey("tts_pitch")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val READING_MODE = stringPreferencesKey("reading_mode")
        val OCR_SCRIPT = stringPreferencesKey("ocr_script")
        val TTS_LANGUAGE = stringPreferencesKey("tts_language")
        val TRANSLATE_ENABLED = booleanPreferencesKey("translate_enabled")
        val TRANSLATE_TARGET = stringPreferencesKey("translate_target")
        val UPDATE_SCHEDULE = stringPreferencesKey("update_schedule")
        val UPDATE_HOUR = intPreferencesKey("update_hour")
        val UPDATE_MINUTE = intPreferencesKey("update_minute")
        val UPDATE_DOW = intPreferencesKey("update_day_of_week")
        val UPDATE_DOM = intPreferencesKey("update_day_of_month")
        val UPDATE_WIFI_ONLY = booleanPreferencesKey("update_wifi_only")
        val LIBRARY_DISPLAY = stringPreferencesKey("library_display_mode")
        val LIBRARY_BADGES = booleanPreferencesKey("library_badges")
        val LAST_REFRESH = longPreferencesKey("last_library_refresh")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val DEFAULT_CATEGORY = longPreferencesKey("default_category_id")
        val BADGE_UNREAD = booleanPreferencesKey("badge_unread")
        val BADGE_DOWNLOADED = booleanPreferencesKey("badge_downloaded")
        val BADGE_LANGUAGE = booleanPreferencesKey("badge_language")
        val COMIC_PAGE_LAYOUT = stringPreferencesKey("comic_page_layout")
        val COMIC_FULLSCREEN = booleanPreferencesKey("comic_fullscreen")
        val TAP_ZONE_LAYOUT = stringPreferencesKey("tap_zone_layout")
        val VOLUME_KEY_PAGING = booleanPreferencesKey("volume_key_paging")
        val DOWNLOAD_LOCATION = stringPreferencesKey("download_location")
        val DOWNLOAD_NEW_CHAPTERS = booleanPreferencesKey("download_new_chapters")
        val REMOVE_AFTER_READ = booleanPreferencesKey("remove_after_read")
        val CONCURRENT_DOWNLOADS = intPreferencesKey("concurrent_downloads")
        val INCLUDE_NSFW = booleanPreferencesKey("include_nsfw_sources")
        val AUTO_UPDATE_EXTENSIONS = booleanPreferencesKey("auto_update_extensions")
        val AUTO_BACKUP = stringPreferencesKey("auto_backup_frequency")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val SECURE_SCREEN = booleanPreferencesKey("secure_screen")
        val INCOGNITO = booleanPreferencesKey("incognito_mode")
    }
}
