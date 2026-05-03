package com.lumen.app.data.fs

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SafRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val KEY_FOLDER_URIS = stringSetPreferencesKey("saf_folder_uris")
        private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val KEY_SEARCH_HISTORY = stringPreferencesKey("search_history")
        private val KEY_VIEWER_LAST_PAGES = stringSetPreferencesKey("viewer_last_pages")
        private val KEY_VIEWER_SCROLL_HORIZONTAL = booleanPreferencesKey("viewer_scroll_horizontal")
    }

    val hasCompletedOnboarding: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDING_DONE] == true
    }

    suspend fun markOnboardingDone() {
        dataStore.edit { it[KEY_ONBOARDING_DONE] = true }
    }

    val folderUris: Flow<Set<Uri>> = dataStore.data.map { prefs ->
        prefs[KEY_FOLDER_URIS].orEmpty().map { Uri.parse(it) }.toSet()
    }

    suspend fun addFolder(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        dataStore.edit { prefs ->
            val current = prefs[KEY_FOLDER_URIS].orEmpty().toMutableSet()
            current.add(treeUri.toString())
            prefs[KEY_FOLDER_URIS] = current
        }
    }

    suspend fun removeFolder(treeUri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {}
        dataStore.edit { prefs ->
            val current = prefs[KEY_FOLDER_URIS].orEmpty().toMutableSet()
            current.remove(treeUri.toString())
            prefs[KEY_FOLDER_URIS] = current
        }
    }

    fun hasPersistedPermission(treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission
        }

    // ── Search history ────────────────────────────────────────────────────────

    val searchHistory: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[KEY_SEARCH_HISTORY].orEmpty()
            .split("|")
            .filter { it.isNotBlank() }
    }

    suspend fun addToSearchHistory(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_SEARCH_HISTORY].orEmpty()
                .split("|")
                .filter { it.isNotBlank() && it != trimmed }
            prefs[KEY_SEARCH_HISTORY] = (listOf(trimmed) + current).take(20).joinToString("|")
        }
    }

    suspend fun removeFromSearchHistory(query: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_SEARCH_HISTORY].orEmpty()
                .split("|")
                .filter { it.isNotBlank() && it != query }
            prefs[KEY_SEARCH_HISTORY] = current.joinToString("|")
        }
    }

    suspend fun clearSearchHistory() {
        dataStore.edit { prefs -> prefs[KEY_SEARCH_HISTORY] = "" }
    }

    // ── PDF viewer scroll mode ────────────────────────────────────────────────

    val viewerScrollHorizontal: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_VIEWER_SCROLL_HORIZONTAL] ?: false
    }

    suspend fun setViewerScrollHorizontal(horizontal: Boolean) {
        dataStore.edit { it[KEY_VIEWER_SCROLL_HORIZONTAL] = horizontal }
    }

    // ── Reading progress ──────────────────────────────────────────────────────

    suspend fun saveLastPage(uri: String, page: Int) {
        val hashKey = uri.hashCode().toString()
        dataStore.edit { prefs ->
            val current = prefs[KEY_VIEWER_LAST_PAGES].orEmpty().toMutableSet()
            current.removeIf { it.startsWith("$hashKey:") }
            current.add("$hashKey:$page")
            prefs[KEY_VIEWER_LAST_PAGES] = current
        }
    }

    suspend fun getLastPage(uri: String): Int? {
        val hashKey = uri.hashCode().toString()
        return dataStore.data.first()[KEY_VIEWER_LAST_PAGES].orEmpty()
            .firstOrNull { it.startsWith("$hashKey:") }
            ?.substringAfter(':')
            ?.toIntOrNull()
    }
}
