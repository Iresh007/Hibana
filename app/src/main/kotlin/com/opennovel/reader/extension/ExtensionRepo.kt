package com.opennovel.reader.extension

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** A user-added extension repository ("extension store"). */
data class ExtensionRepo(
    val url: String,
    val enabled: Boolean = true,
) {
    /** Short label for the list — the owner/repo part of a raw GitHub URL. */
    val displayName: String
        get() = Regex("""githubusercontent\.com/([^/]+)/([^/]+)""").find(url)
            ?.let { "${it.groupValues[1]}/${it.groupValues[2]}" }
            ?: url.removePrefix("https://").substringBefore("/")

    /** LNReader publishes JS plugins; everything else is treated as APK-based. */
    val ecosystem: Ecosystem
        get() = if (url.contains("lnreader", ignoreCase = true)) Ecosystem.LNREADER else Ecosystem.MIHON
}

private val Context.repoStore by preferencesDataStore(name = "extension_repos")

/**
 * Persists the user's extension repositories.
 *
 * Stored as a string set of "url|enabled" rather than a nested structure because
 * DataStore Preferences has no list type, and a repo list is small enough that
 * encoding beats introducing a second serialization format.
 */
class ExtensionRepoStore(private val context: Context) {

    val repos: Flow<List<ExtensionRepo>> = context.repoStore.data.map { prefs ->
        val stored = prefs[REPOS] ?: defaultRepos()
        stored.mapNotNull(::decode).sortedBy { it.displayName.lowercase() }
    }

    suspend fun add(url: String): Boolean {
        val clean = url.trim()
        if (!clean.startsWith("http")) return false
        val current = current()
        if (current.any { it.url.equals(clean, ignoreCase = true) }) return false
        save(current + ExtensionRepo(clean))
        return true
    }

    suspend fun remove(url: String) = save(current().filterNot { it.url == url })

    suspend fun setEnabled(url: String, enabled: Boolean) =
        save(current().map { if (it.url == url) it.copy(enabled = enabled) else it })

    suspend fun resetToDefaults() =
        context.repoStore.edit { it[REPOS] = defaultRepos() }

    private suspend fun current(): List<ExtensionRepo> = repos.first()

    private suspend fun save(list: List<ExtensionRepo>) {
        context.repoStore.edit { prefs ->
            prefs[REPOS] = list.map { "${it.url}|${it.enabled}" }.toSet()
        }
    }

    private fun decode(raw: String): ExtensionRepo? {
        val parts = raw.split("|")
        val url = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        return ExtensionRepo(url, parts.getOrNull(1)?.toBooleanStrictOrNull() ?: true)
    }

    private companion object {
        val REPOS = stringSetPreferencesKey("repos")

        /** Ships with the well-known community repositories pre-filled. */
        fun defaultRepos(): Set<String> = setOf(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json|true",
            "https://raw.githubusercontent.com/lnreader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json|true",
        )
    }
}
