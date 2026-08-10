package eu.kanade.tachiyomi.source

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * A source that exposes user-configurable preferences (mirror domain, login,
 * preferred quality, chapter language, …). The host builds an
 * [androidx.preference.PreferenceScreen], hands it to [setupPreferenceScreen],
 * and renders whatever the extension attached to it.
 *
 * This lives in its own file on purpose: extensions are compiled against the
 * real extensions-lib with `compileOnly`, so their dex references the file-facade
 * class `eu.kanade.tachiyomi.source.ConfigurableSourceKt` for the helpers below.
 * Folding them into another file would rename that facade and every extension
 * calling `sourcePreferences()` would fail to link at runtime.
 */
interface ConfigurableSource : Source {
    fun setupPreferenceScreen(screen: PreferenceScreen)
}

/**
 * Name of the SharedPreferences file a source's settings live in. Both the
 * extension and the host must agree on it, otherwise the user edits one store
 * while the extension reads another.
 */
fun sourcePreferencesKey(sourceId: Long): String = "source_$sourceId"

fun ConfigurableSource.preferenceKey(): String = sourcePreferencesKey(id)

fun ConfigurableSource.sourcePreferences(): SharedPreferences =
    Injekt.get<Application>().getSharedPreferences(preferenceKey(), Context.MODE_PRIVATE)

fun sourcePreferences(key: String): SharedPreferences =
    Injekt.get<Application>().getSharedPreferences(key, Context.MODE_PRIVATE)

/** Older extensions-lib spelling; kept so both generations of extensions link. */
fun ConfigurableSource.getPreferences(): SharedPreferences = sourcePreferences()

fun ConfigurableSource.getPreferencesLazy(): Lazy<SharedPreferences> = lazy { sourcePreferences() }
