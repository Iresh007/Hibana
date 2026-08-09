package com.opennovel.reader.extension.ireader

import android.content.SharedPreferences
import androidx.core.content.edit
import ireader.core.prefs.Preference
import ireader.core.prefs.PreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

/**
 * SharedPreferences-backed [PreferenceStore] handed to IReader extensions through
 * `Dependencies`. IReader's source-api declares the interface but each host app
 * supplies its own persistence, so this is the one piece of the IReader runtime
 * Hibana must implement itself.
 *
 * Extensions get their own prefs file (namespaced per package by the caller), so
 * a misbehaving extension cannot read or clobber app settings.
 */
class IReaderPreferenceStore(private val prefs: SharedPreferences) : PreferenceStore {

    override fun getString(key: String, defaultValue: String): Preference<String> =
        SharedPrefsPreference(
            prefs, key, defaultValue,
            reader = { k, d -> prefs.getString(k, d) ?: d },
            writer = { k, v -> putString(k, v) },
        )

    override fun getLong(key: String, defaultValue: Long): Preference<Long> =
        SharedPrefsPreference(
            prefs, key, defaultValue,
            reader = { k, d -> prefs.getLong(k, d) },
            writer = { k, v -> putLong(k, v) },
        )

    override fun getInt(key: String, defaultValue: Int): Preference<Int> =
        SharedPrefsPreference(
            prefs, key, defaultValue,
            reader = { k, d -> prefs.getInt(k, d) },
            writer = { k, v -> putInt(k, v) },
        )

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> =
        SharedPrefsPreference(
            prefs, key, defaultValue,
            reader = { k, d -> prefs.getFloat(k, d) },
            writer = { k, v -> putFloat(k, v) },
        )

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
        SharedPrefsPreference(
            prefs, key, defaultValue,
            reader = { k, d -> prefs.getBoolean(k, d) },
            writer = { k, v -> putBoolean(k, v) },
        )

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
        SharedPrefsPreference(
            prefs, key, defaultValue,
            reader = { k, d -> prefs.getStringSet(k, d) ?: d },
            writer = { k, v -> putStringSet(k, v) },
        )

    override fun <T> getObject(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = SharedPrefsPreference(
        prefs, key, defaultValue,
        reader = { k, d ->
            prefs.getString(k, null)?.let { runCatching { deserializer(it) }.getOrDefault(d) } ?: d
        },
        writer = { k, v -> putString(k, serializer(v)) },
    )

    override fun <T> getJsonObject(
        key: String,
        defaultValue: T,
        serializer: KSerializer<T>,
        serializersModule: SerializersModule,
    ): Preference<T> {
        val json = Json {
            this.serializersModule = serializersModule
            ignoreUnknownKeys = true
            isLenient = true
        }
        return getObject(
            key = key,
            defaultValue = defaultValue,
            serializer = { json.encodeToString(serializer, it) },
            deserializer = { json.decodeFromString(serializer, it) },
        )
    }
}

/**
 * One typed preference entry. Reads and writes go straight through to
 * SharedPreferences; [changes] bridges the platform change listener to a Flow so
 * extensions can observe settings reactively.
 */
private class SharedPrefsPreference<T>(
    private val prefs: SharedPreferences,
    private val key: String,
    private val defaultValue: T,
    private val reader: (String, T) -> T,
    private val writer: SharedPreferences.Editor.(String, T) -> Unit,
) : Preference<T> {

    override fun key(): String = key

    override fun get(): T = runCatching { reader(key, defaultValue) }.getOrDefault(defaultValue)

    override fun set(value: T) = prefs.edit { writer(key, value) }

    override fun isSet(): Boolean = prefs.contains(key)

    override fun delete() = prefs.edit { remove(key) }

    override fun defaultValue(): T = defaultValue

    override fun changes(): Flow<T> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key) trySend(get())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    override fun stateIn(scope: CoroutineScope): StateFlow<T> =
        changes().stateIn(scope, SharingStarted.Eagerly, get())
}
