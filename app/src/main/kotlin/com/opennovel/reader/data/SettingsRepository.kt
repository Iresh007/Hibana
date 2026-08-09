package com.opennovel.reader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
)

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
        )
    }

    /** Tolerates stored values from older builds instead of crashing on rename. */
    private inline fun <reified T : Enum<T>> enumOr(value: String?, fallback: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

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

        @Suppress("unused") val UNUSED_INT = intPreferencesKey("reserved")
    }
}
