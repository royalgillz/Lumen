package com.lumen.app.data.fs

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
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
        private val KEY_FILTER_OCR_ONLY = booleanPreferencesKey("filter_ocr_only")
        private val KEY_FILTER_SORT_ORDER = stringPreferencesKey("filter_sort_order")
        private val KEY_LIBRARY_SORT_ORDER = stringPreferencesKey("library_sort_order")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_NAV_LAYOUT = stringPreferencesKey("nav_layout")
        private val KEY_LAST_AUTO_RESCAN_AT = longPreferencesKey("last_auto_rescan_at")
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

    // distinct() guards against legacy entries where a '|' inside a query split
    // into duplicate/phantom items — duplicates crash LazyColumn keys.
    val searchHistory: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[KEY_SEARCH_HISTORY].orEmpty()
            .split("|")
            .filter { it.isNotBlank() }
            .distinct()
    }

    suspend fun addToSearchHistory(query: String) {
        // '|' is the storage delimiter; a query containing it would corrupt the list.
        val trimmed = query.trim().replace('|', ' ').trim()
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

    // ── Search filter persistence ─────────────────────────────────────────────

    val savedFilterOcrOnly: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_FILTER_OCR_ONLY] ?: false
    }

    val savedFilterSortOrder: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_FILTER_SORT_ORDER] ?: "RELEVANCE"
    }

    suspend fun saveFilterOcrOnly(ocrOnly: Boolean) {
        dataStore.edit { it[KEY_FILTER_OCR_ONLY] = ocrOnly }
    }

    suspend fun saveFilterSortOrder(sortOrder: String) {
        dataStore.edit { it[KEY_FILTER_SORT_ORDER] = sortOrder }
    }

    // ── Library sort persistence ──────────────────────────────────────────────
    // @spec LIB-SORT-002

    val librarySortOrder: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_LIBRARY_SORT_ORDER] ?: "RECENTLY_ADDED"
    }

    suspend fun saveLibrarySortOrder(order: String) {
        dataStore.edit { it[KEY_LIBRARY_SORT_ORDER] = order }
    }

    // ── Appearance ────────────────────────────────────────────────────────────
    // Raw strings here; parsing (with LIGHT / THREE_TAB defaults) lives in the
    // enums' fromPref so unknown values can never crash a flow collector.
    // @spec SET-APPEAR-001

    val themeMode: Flow<String?> = dataStore.data.map { it[KEY_THEME_MODE] }

    suspend fun saveThemeMode(mode: String) {
        dataStore.edit { it[KEY_THEME_MODE] = mode }
    }

    val navLayout: Flow<String?> = dataStore.data.map { it[KEY_NAV_LAYOUT] }

    suspend fun saveNavLayout(layout: String) {
        dataStore.edit { it[KEY_NAV_LAYOUT] = layout }
    }

    // ── Auto-rescan timestamp ─────────────────────────────────────────────────

    val lastAutoRescanAt: Flow<Long> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_AUTO_RESCAN_AT] ?: 0L
    }

    suspend fun setLastAutoRescanAt(ts: Long) {
        dataStore.edit { it[KEY_LAST_AUTO_RESCAN_AT] = ts }
    }

    // ── Reading progress ──────────────────────────────────────────────────────
    // Entries are "key:page". Keys are Uri.encode(uri) — the encoded form has no
    // raw ':', so the first ':' always separates key from page. Older builds
    // keyed by uri.hashCode(), which could collide; those entries are read as a
    // fallback and replaced on the next save.

    suspend fun saveLastPage(uri: String, page: Int) {
        val encodedKey = Uri.encode(uri)
        val legacyKey = uri.hashCode().toString()
        dataStore.edit { prefs ->
            val current = prefs[KEY_VIEWER_LAST_PAGES].orEmpty().toMutableSet()
            current.removeIf { it.startsWith("$encodedKey:") || it.startsWith("$legacyKey:") }
            current.add("$encodedKey:$page")
            prefs[KEY_VIEWER_LAST_PAGES] = current
        }
    }

    suspend fun getLastPage(uri: String): Int? {
        val entries = dataStore.data.first()[KEY_VIEWER_LAST_PAGES].orEmpty()
        val encodedKey = Uri.encode(uri)
        val legacyKey = uri.hashCode().toString()
        val entry = entries.firstOrNull { it.startsWith("$encodedKey:") }
            ?: entries.firstOrNull { it.startsWith("$legacyKey:") }
        return entry?.substringAfter(':')?.toIntOrNull()
    }
}
