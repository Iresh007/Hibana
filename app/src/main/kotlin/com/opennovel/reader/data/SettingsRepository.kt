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

    // --- reader: image handling ---
    /** Trims the solid margins many scans carry, so the page fills the screen. */
    val cropBorders: Boolean = false,
    val imageScaleType: ImageScaleType = ImageScaleType.FIT_SCREEN,
    val zoomStartPosition: ZoomStart = ZoomStart.AUTOMATIC,
    val landscapeZoom: Boolean = false,
    val doubleTapZoom: Boolean = true,
    val navigateWithPan: Boolean = true,
    /** Side padding for long-strip reading, as a percentage of width. */
    val webtoonSidePadding: Int = 0,

    // --- reader: colour and brightness ---
    val customBrightness: Boolean = false,
    /** -0.75..1.0; negative dims below the system minimum for night reading. */
    val customBrightnessValue: Float = 0f,
    val colorFilter: Boolean = false,
    /** Packed ARGB tint applied over the page. */
    val colorFilterValue: Int = 0,
    val colorFilterMode: ColorFilterMode = ColorFilterMode.OVER,
    val grayscale: Boolean = false,
    val invertedColors: Boolean = false,

    // --- reader: orientation and chrome ---
    val readerOrientation: ReaderOrientation = ReaderOrientation.FREE,
    /** Lets pages draw into the display cutout instead of letterboxing. */
    val drawIntoCutout: Boolean = true,
    val showPageNumber: Boolean = true,
    val pageTransitions: Boolean = true,
    val alwaysShowChapterTransition: Boolean = true,
    /** Flashes the screen between long-strip pages to limit OLED burn-in. */
    val flashOnPageChange: Boolean = false,

    // --- reader: reading flow ---
    val skipReadChapters: Boolean = false,
    val skipFilteredChapters: Boolean = true,
    val skipDuplicateChapters: Boolean = false,

    // --- reader: wide-page handling ---
    val dualPageSplit: Boolean = false,
    val dualPageInvert: Boolean = false,
    val rotateWidePages: Boolean = false,
    val rotateWidePagesInvert: Boolean = false,

    // --- library: update scope ---
    val updateOnlyStarted: Boolean = false,
    val updateOnlyNonCompleted: Boolean = true,
    /** Skips entries whose release cadence says nothing is due yet. */
    val updateOnlyInReleasePeriod: Boolean = false,
    val updateOnlyOnCharging: Boolean = false,
    /** Category ids excluded from the sweep, comma-separated. */
    val updateExcludedCategories: String = "",

    // --- library: behaviour ---
    val chapterSwipeStart: ChapterSwipeAction = ChapterSwipeAction.BOOKMARK,
    val chapterSwipeEnd: ChapterSwipeAction = ChapterSwipeAction.TOGGLE_READ,
    val markDuplicateChapterRead: Boolean = false,
    val hideMissingChapterIndicators: Boolean = false,
    /** Each shelf keeps its own grid/list mode and sort. */
    val categorizedDisplaySettings: Boolean = false,

    // --- downloads ---
    /** Keeps this many chapters ahead of the reading position downloaded. */
    val downloadAhead: Int = 0,
    val downloadNewUnreadOnly: Boolean = false,
    /** Keeps the last N read chapters instead of deleting immediately. */
    val keepReadChapters: Int = 0,
    val removeBookmarkedChapters: Boolean = false,
    val splitTallImages: Boolean = false,
    val concurrentPages: Int = 2,
    val createFolderPerEntry: Boolean = true,

    // --- privacy ---
    val lockWithBiometrics: Boolean = false,
    val lockTimeout: LockTimeout = LockTimeout.ALWAYS,
    val hideNotificationContent: Boolean = false,

    // --- advanced: network ---
    val dnsOverHttps: DohProvider = DohProvider.OFF,
    /** Blank uses the platform default. */
    val userAgent: String = "",
    val verboseLogging: Boolean = false,

    // --- browse ---
    /** Hides results already shelved, instead of only badging them. */
    val hideInLibraryItems: Boolean = false,

    // --- appearance ---
    val tabletUiMode: TabletUiMode = TabletUiMode.AUTOMATIC,
    val relativeTimestamps: Boolean = true,
    val dateFormat: String = "",
    val appTheme: AppTheme = AppTheme.DEFAULT,

    /** Cleared once the first-run guide has been completed or dismissed. */
    val onboardingComplete: Boolean = false,
)

/** How a page is fitted to the viewport. */
enum class ImageScaleType(val label: String) {
    FIT_SCREEN("Fit screen"),
    STRETCH("Stretch"),
    FIT_WIDTH("Fit width"),
    FIT_HEIGHT("Fit height"),
    ORIGINAL("Original size"),
    SMART_FIT("Smart fit"),
}

/** Where a zoomed page starts when it opens. */
enum class ZoomStart(val label: String) {
    AUTOMATIC("Automatic"),
    LEFT("Left"),
    RIGHT("Right"),
    CENTER("Centre"),
}

/** How the colour filter composites over the page. */
enum class ColorFilterMode(val label: String) {
    OVER("Normal"),
    MULTIPLY("Multiply"),
    SCREEN("Screen"),
    OVERLAY("Overlay"),
    LIGHTEN("Lighten"),
    DARKEN("Darken"),
}

enum class ReaderOrientation(val label: String) {
    FREE("Follow system"),
    PORTRAIT("Lock portrait"),
    LANDSCAPE("Lock landscape"),
    LOCKED("Lock current"),
}

/** What a swipe on a chapter row does. */
enum class ChapterSwipeAction(val label: String) {
    TOGGLE_READ("Mark read / unread"),
    BOOKMARK("Bookmark"),
    DOWNLOAD("Download"),
    DISABLED("Nothing"),
}

enum class LockTimeout(val label: String, val minutes: Int) {
    ALWAYS("Always", 0),
    AFTER_1("After 1 minute", 1),
    AFTER_5("After 5 minutes", 5),
    AFTER_15("After 15 minutes", 15),
    NEVER("Never", -1),
}

/**
 * DNS-over-HTTPS provider. The standard fix when a source stops resolving
 * because a network operator blocks it at the DNS layer.
 */
enum class DohProvider(val label: String, val url: String) {
    OFF("Off", ""),
    CLOUDFLARE("Cloudflare", "https://cloudflare-dns.com/dns-query"),
    GOOGLE("Google", "https://dns.google/dns-query"),
    ADGUARD("AdGuard", "https://dns-unfiltered.adguard.com/dns-query"),
    QUAD9("Quad9", "https://dns.quad9.net/dns-query"),
}

enum class TabletUiMode(val label: String) {
    AUTOMATIC("Automatic"),
    ALWAYS("Always"),
    NEVER("Never"),
}

/** Preset palettes, distinct from light/dark which [ThemeMode] controls. */
enum class AppTheme(val label: String) {
    DEFAULT("Hibana"),
    MONET("Material You"),
    LAVENDER("Lavender"),
    MIDNIGHT_DUSK("Midnight dusk"),
    GREEN_APPLE("Green apple"),
    STRAWBERRY("Strawberry"),
    TAKO("Tako"),
    YIN_YANG("Yin & Yang"),
}

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
            cropBorders = p[CROP_BORDERS] ?: false,
            imageScaleType = enumOr(p[IMAGE_SCALE], ImageScaleType.FIT_SCREEN),
            zoomStartPosition = enumOr(p[ZOOM_START], ZoomStart.AUTOMATIC),
            landscapeZoom = p[LANDSCAPE_ZOOM] ?: false,
            doubleTapZoom = p[DOUBLE_TAP_ZOOM] ?: true,
            navigateWithPan = p[NAVIGATE_PAN] ?: true,
            webtoonSidePadding = p[WEBTOON_PADDING] ?: 0,
            customBrightness = p[CUSTOM_BRIGHTNESS] ?: false,
            customBrightnessValue = p[BRIGHTNESS_VALUE] ?: 0f,
            colorFilter = p[COLOR_FILTER] ?: false,
            colorFilterValue = p[COLOR_FILTER_VALUE] ?: 0,
            colorFilterMode = enumOr(p[COLOR_FILTER_MODE], ColorFilterMode.OVER),
            grayscale = p[GRAYSCALE] ?: false,
            invertedColors = p[INVERTED] ?: false,
            readerOrientation = enumOr(p[ORIENTATION], ReaderOrientation.FREE),
            drawIntoCutout = p[CUTOUT] ?: true,
            showPageNumber = p[SHOW_PAGE_NUMBER] ?: true,
            pageTransitions = p[PAGE_TRANSITIONS] ?: true,
            alwaysShowChapterTransition = p[CHAPTER_TRANSITION] ?: true,
            flashOnPageChange = p[FLASH_PAGE] ?: false,
            skipReadChapters = p[SKIP_READ] ?: false,
            skipFilteredChapters = p[SKIP_FILTERED] ?: true,
            skipDuplicateChapters = p[SKIP_DUPE] ?: false,
            dualPageSplit = p[DUAL_SPLIT] ?: false,
            dualPageInvert = p[DUAL_INVERT] ?: false,
            rotateWidePages = p[PAGE_ROTATE] ?: false,
            rotateWidePagesInvert = p[PAGE_ROTATE_INVERT] ?: false,
            updateOnlyStarted = p[UPDATE_ONLY_STARTED] ?: false,
            updateOnlyNonCompleted = p[UPDATE_ONLY_ONGOING] ?: true,
            updateOnlyInReleasePeriod = p[UPDATE_IN_PERIOD] ?: false,
            updateOnlyOnCharging = p[UPDATE_CHARGING] ?: false,
            updateExcludedCategories = p[UPDATE_EXCLUDED_CATS] ?: "",
            chapterSwipeStart = enumOr(p[SWIPE_START], ChapterSwipeAction.BOOKMARK),
            chapterSwipeEnd = enumOr(p[SWIPE_END], ChapterSwipeAction.TOGGLE_READ),
            markDuplicateChapterRead = p[MARK_DUPE_READ] ?: false,
            hideMissingChapterIndicators = p[HIDE_GAPS] ?: false,
            categorizedDisplaySettings = p[CATEGORIZED_DISPLAY] ?: false,
            downloadAhead = p[DOWNLOAD_AHEAD] ?: 0,
            downloadNewUnreadOnly = p[DOWNLOAD_UNREAD_ONLY] ?: false,
            keepReadChapters = p[KEEP_READ] ?: 0,
            removeBookmarkedChapters = p[REMOVE_BOOKMARKED] ?: false,
            splitTallImages = p[SPLIT_TALL] ?: false,
            concurrentPages = p[CONCURRENT_PAGES] ?: 2,
            createFolderPerEntry = p[FOLDER_PER_ENTRY] ?: true,
            lockWithBiometrics = p[BIOMETRICS] ?: false,
            lockTimeout = enumOr(p[LOCK_TIMEOUT], LockTimeout.ALWAYS),
            hideNotificationContent = p[HIDE_NOTIF_CONTENT] ?: false,
            dnsOverHttps = enumOr(p[DOH], DohProvider.OFF),
            userAgent = p[USER_AGENT] ?: "",
            verboseLogging = p[VERBOSE_LOG] ?: false,
            hideInLibraryItems = p[HIDE_IN_LIBRARY] ?: false,
            tabletUiMode = enumOr(p[TABLET_UI], TabletUiMode.AUTOMATIC),
            relativeTimestamps = p[RELATIVE_TIME] ?: true,
            dateFormat = p[DATE_FORMAT] ?: "",
            appTheme = enumOr(p[APP_THEME], AppTheme.DEFAULT),
            onboardingComplete = p[ONBOARDING_DONE] ?: false,
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

    // --- reader: image handling ---
    suspend fun setCropBorders(v: Boolean) = edit { it[CROP_BORDERS] = v }
    suspend fun setImageScaleType(v: ImageScaleType) = edit { it[IMAGE_SCALE] = v.name }
    suspend fun setZoomStartPosition(v: ZoomStart) = edit { it[ZOOM_START] = v.name }
    suspend fun setLandscapeZoom(v: Boolean) = edit { it[LANDSCAPE_ZOOM] = v }
    suspend fun setDoubleTapZoom(v: Boolean) = edit { it[DOUBLE_TAP_ZOOM] = v }
    suspend fun setNavigateWithPan(v: Boolean) = edit { it[NAVIGATE_PAN] = v }
    suspend fun setWebtoonSidePadding(v: Int) = edit { it[WEBTOON_PADDING] = v.coerceIn(0, 25) }

    // --- reader: colour and brightness ---
    suspend fun setCustomBrightness(v: Boolean) = edit { it[CUSTOM_BRIGHTNESS] = v }
    suspend fun setCustomBrightnessValue(v: Float) = edit { it[BRIGHTNESS_VALUE] = v.coerceIn(-0.75f, 1f) }
    suspend fun setColorFilter(v: Boolean) = edit { it[COLOR_FILTER] = v }
    suspend fun setColorFilterValue(v: Int) = edit { it[COLOR_FILTER_VALUE] = v }
    suspend fun setColorFilterMode(v: ColorFilterMode) = edit { it[COLOR_FILTER_MODE] = v.name }
    suspend fun setGrayscale(v: Boolean) = edit { it[GRAYSCALE] = v }
    suspend fun setInvertedColors(v: Boolean) = edit { it[INVERTED] = v }

    // --- reader: orientation and chrome ---
    suspend fun setReaderOrientation(v: ReaderOrientation) = edit { it[ORIENTATION] = v.name }
    suspend fun setDrawIntoCutout(v: Boolean) = edit { it[CUTOUT] = v }
    suspend fun setShowPageNumber(v: Boolean) = edit { it[SHOW_PAGE_NUMBER] = v }
    suspend fun setPageTransitions(v: Boolean) = edit { it[PAGE_TRANSITIONS] = v }
    suspend fun setAlwaysShowChapterTransition(v: Boolean) = edit { it[CHAPTER_TRANSITION] = v }
    suspend fun setFlashOnPageChange(v: Boolean) = edit { it[FLASH_PAGE] = v }

    // --- reader: reading flow ---
    suspend fun setSkipReadChapters(v: Boolean) = edit { it[SKIP_READ] = v }
    suspend fun setSkipFilteredChapters(v: Boolean) = edit { it[SKIP_FILTERED] = v }
    suspend fun setSkipDuplicateChapters(v: Boolean) = edit { it[SKIP_DUPE] = v }

    // --- reader: wide pages ---
    suspend fun setDualPageSplit(v: Boolean) = edit { it[DUAL_SPLIT] = v }
    suspend fun setDualPageInvert(v: Boolean) = edit { it[DUAL_INVERT] = v }
    suspend fun setRotateWidePages(v: Boolean) = edit { it[PAGE_ROTATE] = v }
    suspend fun setRotateWidePagesInvert(v: Boolean) = edit { it[PAGE_ROTATE_INVERT] = v }

    // --- library ---
    suspend fun setUpdateOnlyStarted(v: Boolean) = edit { it[UPDATE_ONLY_STARTED] = v }
    suspend fun setUpdateOnlyNonCompleted(v: Boolean) = edit { it[UPDATE_ONLY_ONGOING] = v }
    suspend fun setUpdateOnlyInReleasePeriod(v: Boolean) = edit { it[UPDATE_IN_PERIOD] = v }
    suspend fun setUpdateOnlyOnCharging(v: Boolean) = edit { it[UPDATE_CHARGING] = v }
    suspend fun setUpdateExcludedCategories(ids: Set<Long>) =
        edit { it[UPDATE_EXCLUDED_CATS] = ids.joinToString(",") }
    suspend fun setChapterSwipeStart(v: ChapterSwipeAction) = edit { it[SWIPE_START] = v.name }
    suspend fun setChapterSwipeEnd(v: ChapterSwipeAction) = edit { it[SWIPE_END] = v.name }
    suspend fun setMarkDuplicateChapterRead(v: Boolean) = edit { it[MARK_DUPE_READ] = v }
    suspend fun setHideMissingChapterIndicators(v: Boolean) = edit { it[HIDE_GAPS] = v }
    suspend fun setCategorizedDisplaySettings(v: Boolean) = edit { it[CATEGORIZED_DISPLAY] = v }

    // --- downloads ---
    suspend fun setDownloadAhead(v: Int) = edit { it[DOWNLOAD_AHEAD] = v.coerceIn(0, 10) }
    suspend fun setDownloadNewUnreadOnly(v: Boolean) = edit { it[DOWNLOAD_UNREAD_ONLY] = v }
    suspend fun setKeepReadChapters(v: Int) = edit { it[KEEP_READ] = v.coerceIn(0, 20) }
    suspend fun setRemoveBookmarkedChapters(v: Boolean) = edit { it[REMOVE_BOOKMARKED] = v }
    suspend fun setSplitTallImages(v: Boolean) = edit { it[SPLIT_TALL] = v }
    suspend fun setConcurrentPages(v: Int) = edit { it[CONCURRENT_PAGES] = v.coerceIn(1, 8) }
    suspend fun setCreateFolderPerEntry(v: Boolean) = edit { it[FOLDER_PER_ENTRY] = v }

    // --- privacy ---
    suspend fun setLockWithBiometrics(v: Boolean) = edit { it[BIOMETRICS] = v }
    suspend fun setLockTimeout(v: LockTimeout) = edit { it[LOCK_TIMEOUT] = v.name }
    suspend fun setHideNotificationContent(v: Boolean) = edit { it[HIDE_NOTIF_CONTENT] = v }

    // --- advanced ---
    suspend fun setDnsOverHttps(v: DohProvider) = edit { it[DOH] = v.name }
    suspend fun setUserAgent(v: String) = edit { it[USER_AGENT] = v }
    suspend fun setVerboseLogging(v: Boolean) = edit { it[VERBOSE_LOG] = v }

    // --- browse / appearance ---
    suspend fun setHideInLibraryItems(v: Boolean) = edit { it[HIDE_IN_LIBRARY] = v }
    suspend fun setTabletUiMode(v: TabletUiMode) = edit { it[TABLET_UI] = v.name }
    suspend fun setRelativeTimestamps(v: Boolean) = edit { it[RELATIVE_TIME] = v }
    suspend fun setDateFormat(v: String) = edit { it[DATE_FORMAT] = v }
    suspend fun setAppTheme(v: AppTheme) = edit { it[APP_THEME] = v.name }
    suspend fun setOnboardingComplete(v: Boolean) = edit { it[ONBOARDING_DONE] = v }

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

        val CROP_BORDERS = booleanPreferencesKey("crop_borders")
        val IMAGE_SCALE = stringPreferencesKey("image_scale_type")
        val ZOOM_START = stringPreferencesKey("zoom_start")
        val LANDSCAPE_ZOOM = booleanPreferencesKey("landscape_zoom")
        val DOUBLE_TAP_ZOOM = booleanPreferencesKey("double_tap_zoom")
        val NAVIGATE_PAN = booleanPreferencesKey("navigate_pan")
        val WEBTOON_PADDING = intPreferencesKey("webtoon_side_padding")

        val CUSTOM_BRIGHTNESS = booleanPreferencesKey("custom_brightness")
        val BRIGHTNESS_VALUE = floatPreferencesKey("custom_brightness_value")
        val COLOR_FILTER = booleanPreferencesKey("color_filter")
        val COLOR_FILTER_VALUE = intPreferencesKey("color_filter_value")
        val COLOR_FILTER_MODE = stringPreferencesKey("color_filter_mode")
        val GRAYSCALE = booleanPreferencesKey("grayscale")
        val INVERTED = booleanPreferencesKey("inverted_colors")

        val ORIENTATION = stringPreferencesKey("reader_orientation")
        val CUTOUT = booleanPreferencesKey("draw_into_cutout")
        val SHOW_PAGE_NUMBER = booleanPreferencesKey("show_page_number")
        val PAGE_TRANSITIONS = booleanPreferencesKey("page_transitions")
        val CHAPTER_TRANSITION = booleanPreferencesKey("chapter_transition")
        val FLASH_PAGE = booleanPreferencesKey("flash_page")

        val SKIP_READ = booleanPreferencesKey("skip_read_chapters")
        val SKIP_FILTERED = booleanPreferencesKey("skip_filtered_chapters")
        val SKIP_DUPE = booleanPreferencesKey("skip_duplicate_chapters")

        val DUAL_SPLIT = booleanPreferencesKey("dual_page_split")
        val DUAL_INVERT = booleanPreferencesKey("dual_page_invert")
        val PAGE_ROTATE = booleanPreferencesKey("page_rotate")
        val PAGE_ROTATE_INVERT = booleanPreferencesKey("page_rotate_invert")

        val UPDATE_ONLY_STARTED = booleanPreferencesKey("update_only_started")
        val UPDATE_ONLY_ONGOING = booleanPreferencesKey("update_only_non_completed")
        val UPDATE_IN_PERIOD = booleanPreferencesKey("update_only_in_release_period")
        val UPDATE_CHARGING = booleanPreferencesKey("update_only_charging")
        val UPDATE_EXCLUDED_CATS = stringPreferencesKey("update_excluded_categories")

        val SWIPE_START = stringPreferencesKey("chapter_swipe_start")
        val SWIPE_END = stringPreferencesKey("chapter_swipe_end")
        val MARK_DUPE_READ = booleanPreferencesKey("mark_duplicate_read")
        val HIDE_GAPS = booleanPreferencesKey("hide_missing_chapter_indicators")
        val CATEGORIZED_DISPLAY = booleanPreferencesKey("categorized_display_settings")

        val DOWNLOAD_AHEAD = intPreferencesKey("download_ahead")
        val DOWNLOAD_UNREAD_ONLY = booleanPreferencesKey("download_new_unread_only")
        val KEEP_READ = intPreferencesKey("keep_read_chapters")
        val REMOVE_BOOKMARKED = booleanPreferencesKey("remove_bookmarked_chapters")
        val SPLIT_TALL = booleanPreferencesKey("split_tall_images")
        val CONCURRENT_PAGES = intPreferencesKey("concurrent_pages")
        val FOLDER_PER_ENTRY = booleanPreferencesKey("create_folder_per_entry")

        val BIOMETRICS = booleanPreferencesKey("lock_with_biometrics")
        val LOCK_TIMEOUT = stringPreferencesKey("lock_timeout")
        val HIDE_NOTIF_CONTENT = booleanPreferencesKey("hide_notification_content")

        val DOH = stringPreferencesKey("dns_over_https")
        val USER_AGENT = stringPreferencesKey("user_agent")
        val VERBOSE_LOG = booleanPreferencesKey("verbose_logging")

        val HIDE_IN_LIBRARY = booleanPreferencesKey("hide_in_library_items")
        val TABLET_UI = stringPreferencesKey("tablet_ui_mode")
        val RELATIVE_TIME = booleanPreferencesKey("relative_timestamps")
        val DATE_FORMAT = stringPreferencesKey("date_format")
        val APP_THEME = stringPreferencesKey("app_theme")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_complete")
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
