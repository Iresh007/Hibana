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
)

enum class ThemeMode { LIGHT, DARK, SYSTEM, SEPIA, BLACK }

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
        )
    }

    suspend fun setFontScale(v: Float) = edit { it[FONT_SCALE] = v }
    suspend fun setLineSpacing(v: Float) = edit { it[LINE_SPACING] = v }
    suspend fun setThemeMode(v: ThemeMode) = edit { it[THEME_MODE] = v.name }
    suspend fun setFontFamily(v: String) = edit { it[FONT_FAMILY] = v }
    suspend fun setTtsSpeed(v: Float) = edit { it[TTS_SPEED] = v }
    suspend fun setTtsPitch(v: Float) = edit { it[TTS_PITCH] = v }
    suspend fun setTtsVoice(v: String) = edit { it[TTS_VOICE] = v }
    suspend fun setKeepScreenOn(v: Boolean) = edit { it[KEEP_SCREEN_ON] = v }

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

        @Suppress("unused") val UNUSED_INT = intPreferencesKey("reserved")
    }
}
