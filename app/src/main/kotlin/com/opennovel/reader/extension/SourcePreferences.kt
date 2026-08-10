package com.opennovel.reader.extension

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.TwoStatePreference
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.sourcePreferencesKey
import com.opennovel.reader.source.Source as HostSource

/**
 * One row of a source's settings screen, flattened out of the androidx
 * [PreferenceScreen][androidx.preference.PreferenceScreen] the extension built.
 *
 * The androidx `Preference` object is carried along rather than copied out: it
 * owns the extension's `OnPreferenceChangeListener` (which sources use to reject
 * or rewrite values) and it owns persistence, so writes must go through it.
 */
sealed interface SourcePreferenceItem {
    val title: String

    /** A `PreferenceCategory` header. Purely visual. */
    data class Group(override val title: String) : SourcePreferenceItem

    /** A plain `Preference` with no value — informational text or a link. */
    data class Info(
        override val title: String,
        val summary: String?,
    ) : SourcePreferenceItem

    data class Switch(
        override val title: String,
        val summary: String?,
        val enabled: Boolean,
        val value: Boolean,
        val native: Preference,
    ) : SourcePreferenceItem

    data class Text(
        override val title: String,
        val summary: String?,
        val enabled: Boolean,
        val value: String,
        val native: Preference,
    ) : SourcePreferenceItem

    data class Select(
        override val title: String,
        val summary: String?,
        val enabled: Boolean,
        val value: String,
        val entries: List<String>,
        val entryValues: List<String>,
        val native: Preference,
    ) : SourcePreferenceItem

    data class MultiSelect(
        override val title: String,
        val summary: String?,
        val enabled: Boolean,
        val value: Set<String>,
        val entries: List<String>,
        val entryValues: List<String>,
        val native: Preference,
    ) : SourcePreferenceItem
}

/**
 * Reads and writes the settings a Tachiyomi-style extension declares.
 *
 * A large share of Keiyoushi sources need a mirror or domain configured before
 * they return anything, and the only way to learn what they accept is to ask
 * them: `ConfigurableSource.setupPreferenceScreen` is the contract, and the
 * extension populates the screen with androidx `Preference` objects at call
 * time. So the host builds a real (never-inflated) preference hierarchy, lets
 * the extension fill it, then walks it and renders the result in Compose.
 */
object SourcePreferences {

    /** The extension side of the same store, so both read and write one file. */
    fun sharedPreferences(context: Context, sourceId: Long): SharedPreferences =
        context.getSharedPreferences(sourcePreferencesKey(sourceId), Context.MODE_PRIVATE)

    /**
     * The configurable extension behind a host source, or null if this source
     * declares no settings. Only the Tachiyomi/Manatan adapter can carry one —
     * IReader's source-api (1.5.1) has no preference-screen contract at all and
     * LNReader plugins declare none.
     */
    fun configurableOf(source: HostSource?): ConfigurableSource? =
        (source as? MihonSourceAdapter)?.configurable

    fun isConfigurable(source: HostSource?): Boolean = configurableOf(source) != null

    /**
     * Builds the extension's preference screen and flattens it for rendering.
     *
     * `RestrictedApi`: `PreferenceManager(Context)` and `createPreferenceScreen`
     * are library-group-restricted because they are normally reached through
     * `PreferenceFragmentCompat`. There is no fragment here — the screen is
     * never attached to a view — and this is exactly how Mihon does it. The
     * manager is pointed at the source's own prefs file so every value the
     * extension attached is initialised from, and persisted back to, the store
     * the extension itself reads.
     */
    @Suppress("RestrictedApi")
    fun read(context: Context, source: ConfigurableSource): List<SourcePreferenceItem> = runCatching {
        val manager = PreferenceManager(context).apply {
            sharedPreferencesName = sourcePreferencesKey(source.id)
            sharedPreferencesMode = Context.MODE_PRIVATE
        }
        val screen = manager.createPreferenceScreen(context)
        source.setupPreferenceScreen(screen)
        buildList { flatten(screen, this) }
    }.getOrDefault(emptyList())

    private fun flatten(group: PreferenceGroup, into: MutableList<SourcePreferenceItem>) {
        for (index in 0 until group.preferenceCount) {
            val preference = group.getPreference(index)
            if (!preference.isVisible) continue
            when (preference) {
                is PreferenceCategory -> {
                    into += SourcePreferenceItem.Group(preference.title?.toString().orEmpty())
                    flatten(preference, into)
                }
                is PreferenceGroup -> flatten(preference, into)
                else -> into += preference.toItem()
            }
        }
    }

    private fun Preference.toItem(): SourcePreferenceItem {
        val label = title?.toString().orEmpty().ifBlank { key.orEmpty() }
        val hint = summary?.toString()
        return when (this) {
            is TwoStatePreference ->
                SourcePreferenceItem.Switch(label, hint, isEnabled, isChecked, this)
            is EditTextPreference ->
                SourcePreferenceItem.Text(label, hint, isEnabled, text.orEmpty(), this)
            is ListPreference ->
                SourcePreferenceItem.Select(
                    title = label,
                    summary = hint,
                    enabled = isEnabled,
                    value = value.orEmpty(),
                    entries = entries?.map { it.toString() }.orEmpty(),
                    entryValues = entryValues?.map { it.toString() }.orEmpty(),
                    native = this,
                )
            is MultiSelectListPreference ->
                SourcePreferenceItem.MultiSelect(
                    title = label,
                    summary = hint,
                    enabled = isEnabled,
                    value = values.orEmpty(),
                    entries = entries?.map { it.toString() }.orEmpty(),
                    entryValues = entryValues?.map { it.toString() }.orEmpty(),
                    native = this,
                )
            else -> SourcePreferenceItem.Info(label, hint)
        }
    }

    /**
     * Applies a new value, honouring the extension's change listener.
     *
     * Sources use `OnPreferenceChangeListener` to validate input (a malformed
     * mirror URL) or to do the write themselves; returning false there means
     * "reject", so nothing may be persisted. When it accepts, the value is set
     * on the androidx object, which persists through the manager into the
     * source's own prefs file — the same store the extension reads at runtime.
     *
     * @return the value now in effect, or null when the extension rejected it.
     */
    fun <T : Any> write(preference: Preference, value: T): T? {
        if (!runCatching { preference.callChangeListener(value) }.getOrDefault(true)) return null
        runCatching {
            when (preference) {
                is TwoStatePreference -> preference.isChecked = value as Boolean
                is EditTextPreference -> preference.text = value as String
                is ListPreference -> preference.value = value as String
                is MultiSelectListPreference -> {
                    @Suppress("UNCHECKED_CAST")
                    preference.values = value as Set<String>
                }
                else -> Unit
            }
        }
        return value
    }
}
