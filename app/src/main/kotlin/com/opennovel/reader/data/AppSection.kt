package com.opennovel.reader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.opennovel.reader.data.db.ContentType
import com.opennovel.reader.extension.Ecosystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sectionStore: DataStore<Preferences> by preferencesDataStore(name = "sections")

/**
 * The two halves of the app: comics and prose.
 *
 * They are separate sections rather than a filter over one shelf because they
 * are read differently, sourced from different extension ecosystems, and want
 * different defaults — a page-fit setting is meaningless for prose, and a font
 * size is meaningless for a scan. Keeping them apart means neither section
 * shows a control that cannot apply to it.
 */
enum class AppSection(val label: String, val contentType: ContentType) {
    COMIC("Manga & Manhwa", ContentType.COMIC),
    NOVEL("Novels", ContentType.NOVEL),
    ;

    companion object {
        fun from(value: String?): AppSection =
            entries.firstOrNull { it.name == value } ?: COMIC

        /**
         * Which section an extension ecosystem belongs to.
         *
         * Mihon and Manatan package comic sources; IReader and LNReader package
         * prose. This is what lets the Browse and Extensions lists show only
         * what the current section can actually use, instead of one list mixing
         * both and leaving the user to guess.
         */
        fun of(ecosystem: Ecosystem): AppSection = when (ecosystem) {
            Ecosystem.MIHON, Ecosystem.MANATAN -> COMIC
            Ecosystem.IREADER, Ecosystem.LNREADER, Ecosystem.BUILTIN -> NOVEL
        }
    }
}

/**
 * Preferences that differ between the two sections, stored per section.
 *
 * Keys are suffixed with the section name rather than held in two objects, so
 * adding a per-section preference is one key and one accessor, and a preference
 * that turns out not to need splitting can move back to [SettingsRepository]
 * without a migration.
 */
data class SectionSettings(
    val fontScale: Float = 1.0f,
    val lineSpacing: Float = 1.4f,
    val fontFamily: String = "serif",
    val readingMode: ReadingMode = ReadingMode.WEBTOON,
    val keepScreenOn: Boolean = true,
    val libraryDisplayMode: LibraryDisplayMode = LibraryDisplayMode.COMFORTABLE_GRID,
    val showLibraryBadges: Boolean = true,
)

/** Reads and writes the active section and the preferences scoped to it. */
class SectionRepository(private val context: Context) {

    val activeSection: Flow<AppSection> =
        context.sectionStore.data.map { AppSection.from(it[ACTIVE_SECTION]) }

    suspend fun setActiveSection(section: AppSection) =
        context.sectionStore.edit { it[ACTIVE_SECTION] = section.name }

    fun settings(section: AppSection): Flow<SectionSettings> =
        context.sectionStore.data.map { p ->
            SectionSettings(
                fontScale = p[key(section, "font_scale", ::floatPreferencesKey)] ?: 1.0f,
                lineSpacing = p[key(section, "line_spacing", ::floatPreferencesKey)] ?: 1.4f,
                fontFamily = p[key(section, "font_family", ::stringPreferencesKey)] ?: "serif",
                readingMode = enumOr(
                    p[key(section, "reading_mode", ::stringPreferencesKey)],
                    // Prose has no page layout; webtoon is meaningless for it,
                    // so the two sections start from different defaults.
                    if (section == AppSection.COMIC) ReadingMode.WEBTOON else ReadingMode.PAGED_VERTICAL,
                ),
                keepScreenOn = p[key(section, "keep_screen_on", ::booleanPreferencesKey)] ?: true,
                libraryDisplayMode = enumOr(
                    p[key(section, "library_display", ::stringPreferencesKey)],
                    LibraryDisplayMode.COMFORTABLE_GRID,
                ),
                showLibraryBadges = p[key(section, "library_badges", ::booleanPreferencesKey)] ?: true,
            )
        }

    suspend fun setFontScale(section: AppSection, value: Float) =
        edit { it[key(section, "font_scale", ::floatPreferencesKey)] = value }

    suspend fun setLineSpacing(section: AppSection, value: Float) =
        edit { it[key(section, "line_spacing", ::floatPreferencesKey)] = value }

    suspend fun setFontFamily(section: AppSection, value: String) =
        edit { it[key(section, "font_family", ::stringPreferencesKey)] = value }

    suspend fun setReadingMode(section: AppSection, value: ReadingMode) =
        edit { it[key(section, "reading_mode", ::stringPreferencesKey)] = value.name }

    suspend fun setKeepScreenOn(section: AppSection, value: Boolean) =
        edit { it[key(section, "keep_screen_on", ::booleanPreferencesKey)] = value }

    suspend fun setLibraryDisplayMode(section: AppSection, value: LibraryDisplayMode) =
        edit { it[key(section, "library_display", ::stringPreferencesKey)] = value.name }

    suspend fun setShowLibraryBadges(section: AppSection, value: Boolean) =
        edit { it[key(section, "library_badges", ::booleanPreferencesKey)] = value }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.sectionStore.edit(block)
    }

    private fun <T> key(
        section: AppSection,
        name: String,
        factory: (String) -> Preferences.Key<T>,
    ): Preferences.Key<T> = factory("${section.name.lowercase()}_$name")

    private inline fun <reified T : Enum<T>> enumOr(value: String?, fallback: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private companion object {
        val ACTIVE_SECTION = stringPreferencesKey("active_section")
    }
}
