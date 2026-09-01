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

    // Read AND write persisted: the system picker offers both on a tree pick,
    // and the write half is what makes on-device file rename possible. Folders
    // granted by older builds hold read-only — they need a re-pick to upgrade
    // (Android persists only grants currently held).
    // @spec LIB-REN-005
    suspend fun addFolder(treeUri: Uri) {
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, readWrite)
        } catch (_: SecurityException) {
            // A picker that offered no write grant: keep the folder usable
            // read-only; rename stays gated on hasWritePermission.
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        dataStore.edit { prefs ->
            val current = prefs[KEY_FOLDER_URIS].orEmpty().toMutableSet()
            current.add(treeUri.toString())
            prefs[KEY_FOLDER_URIS] = current
        }
    }

    // @spec LIB-REN-005
    suspend fun removeFolder(treeUri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {}
        }
        dataStore.edit { prefs ->
            val current = prefs[KEY_FOLDER_URIS].orEmpty().toMutableSet()
            current.remove(treeUri.toString())
            prefs[KEY_FOLDER_URIS] = current
        }
    }

    /** Whether the file-rename toggle can act in this folder (LIB-REN-004). */
    fun hasWritePermission(treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isWritePermission
        }

    fun hasPersistedPermission(treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission
        }

    // ── External-open grants ──────────────────────────────────────────────────

    /** Takes a persistable read grant for an externally opened document when the
     *  sender offered one; most mailers don't — failure is fine, the grant stays
     *  transient and the recents entry self-heals when it dies. */
    // @spec LIB-REC-004
    fun takePersistableReadIfOffered(uri: Uri, intentFlags: Int) {
        if (intentFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {}
    }

    /** Releases any persisted read grant held for a deleted external-opens row —
     *  the app never retains access to a document it no longer references. */
    // @spec LIB-REC-007
    fun releasePersistedRead(uriString: String) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        val held = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (!held) return
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {}
    }

    /** Whether Lumen holds a persisted read grant for exactly [uri] — the
     *  single-document check behind the external-access offers (a doc URI
     *  never string-equals a tree URI, so tree grants can't false-positive;
     *  folder coverage is [anyLibraryTreeCovers]). */
    // @spec LIB-EXT-007
    fun hasPersistedRead(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }

    /** Whether any library folder covers [docUri] — an externally opened
     *  document that is already (or will be) indexed via a granted tree needs
     *  no access offer. Pure matching lives in [anyLibraryTreeCovers]. */
    // @spec LIB-EXT-007, LIB-EXT-012
    suspend fun anyLibraryTreeCovers(docUri: String): Boolean =
        anyLibraryTreeCovers(dataStore.data.first()[KEY_FOLDER_URIS].orEmpty(), docUri)

    /**
     * Whether a library folder covers [docUri] AND that folder's tree grant is
     * actually held right now. Coverage on paper is not access: library rows
     * survive tree-grant loss (the lost-permission banner exists for exactly
     * that), so releasing a doc-form grant — or skipping a keep-access record —
     * on the strength of a dead tree destroys the one working access path.
     */
    /** Every persisted non-tree (single-document) read grant currently held —
     *  the startup orphan sweep's input (LIB-EXT-020). */
    fun persistedDocGrantUris(): List<String> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && it.uri.pathSegments.firstOrNull() != "tree" }
            .map { it.uri.toString() }

    // @spec LIB-EXT-018
    suspend fun anyLiveLibraryTreeCovers(docUri: String): Boolean {
        val covering = dataStore.data.first()[KEY_FOLDER_URIS].orEmpty()
            .filter { libraryTreeCoversDoc(it, docUri) }
        if (covering.isEmpty()) return false
        val held = context.contentResolver.persistedUriPermissions
            .asSequence()
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()
        return covering.any { it in held }
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

    /** Every saved reading position, keyed by the raw stored key (encoded URI,
     *  or a legacy `uri.hashCode()` key from older builds) — one decode per
     *  store change, so list rows never read DataStore individually. Lookup by
     *  document URI goes through `lastPageFor`. */
    // @spec LIB-PRG-002
    val lastPages: Flow<Map<String, Int>> = dataStore.data.map { prefs ->
        parseLastPageEntries(prefs[KEY_VIEWER_LAST_PAGES].orEmpty())
    }

    suspend fun getLastPage(uri: String): Int? {
        val entries = dataStore.data.first()[KEY_VIEWER_LAST_PAGES].orEmpty()
        val encodedKey = Uri.encode(uri)
        val legacyKey = uri.hashCode().toString()
        val entry = entries.firstOrNull { it.startsWith("$encodedKey:") }
            ?: entries.firstOrNull { it.startsWith("$legacyKey:") }
        return entry?.substringAfter(':')?.toIntOrNull()
    }

    /** Moves a document's reading position to its post-rename URI, dropping
     *  the encoded and legacy hash-keyed old entries. */
    // @spec LIB-REN-002
    suspend fun rewriteLastPage(oldUri: String, newUri: String) {
        dataStore.edit { prefs ->
            prefs[KEY_VIEWER_LAST_PAGES] = rewriteLastPageEntries(
                entries = prefs[KEY_VIEWER_LAST_PAGES].orEmpty(),
                oldKeys = setOf(Uri.encode(oldUri), oldUri.hashCode().toString()),
                newKey = Uri.encode(newUri),
                ghostKeys = setOf(Uri.encode(newUri), newUri.hashCode().toString()),
            )
        }
    }

    /** Keep-access variant of [rewriteLastPage]: the old URI's position wins
     *  (it is the session the user just read), but when the old URI has none,
     *  a position already stored under the new URI is KEPT — in a re-pick the
     *  new URI may hold this same document's live position from an earlier
     *  session, never a dead file's ghost. */
    // @spec LIB-EXT-017
    suspend fun mergeLastPage(oldUri: String, newUri: String) {
        dataStore.edit { prefs ->
            prefs[KEY_VIEWER_LAST_PAGES] = rewriteLastPageEntries(
                entries = prefs[KEY_VIEWER_LAST_PAGES].orEmpty(),
                oldKeys = setOf(Uri.encode(oldUri), oldUri.hashCode().toString()),
                newKey = Uri.encode(newUri),
                ghostKeys = setOf(Uri.encode(newUri), newUri.hashCode().toString()),
                keepNewWhenOldMissing = true,
            )
        }
    }
}

/** Pure decode of the "key:page" entry set. Keys are `Uri.encode(uri)` (the
 *  encoded form has no raw ':') or a legacy hashCode string, so the first ':'
 *  always ends the key; malformed entries are skipped, never thrown on. */
// @spec LIB-PRG-002
internal fun parseLastPageEntries(entries: Set<String>): Map<String, Int> =
    buildMap {
        for (entry in entries) {
            val sep = entry.indexOf(':')
            if (sep <= 0) continue
            val page = entry.substring(sep + 1).toIntOrNull() ?: continue
            put(entry.substring(0, sep), page)
        }
    }

/** Pure rewrite of the "key:page" entry set — the first old key with an entry
 *  wins (callers list the encoded key before the legacy hash key). Any
 *  pre-existing entry under the new URI's keys — encoded or legacy hash form
 *  ([ghostKeys]) — is a ghost left by a dead file that had this path: always
 *  purged, even when the document itself carried no saved position, so the
 *  renamed document can never inherit it (getLastPage falls back to a legacy
 *  entry exactly when no encoded one exists).
 *
 *  [keepNewWhenOldMissing] flips that last rule for the keep-access merge,
 *  where the new URI's entries belong to THIS document, not a dead file: when
 *  the old keys carry no position, the new URI's entries are kept untouched
 *  instead of purged. */
// @spec LIB-REN-002, LIB-REN-014, LIB-EXT-017
internal fun rewriteLastPageEntries(
    entries: Set<String>,
    oldKeys: Set<String>,
    newKey: String,
    ghostKeys: Set<String> = setOf(newKey),
    keepNewWhenOldMissing: Boolean = false,
): Set<String> {
    val ghosts = entries.filter { e -> (ghostKeys + newKey).any { e.startsWith("$it:") } }
    val stale = entries.filter { e -> oldKeys.any { e.startsWith("$it:") } }
    val page = oldKeys.asSequence()
        .mapNotNull { key -> entries.firstOrNull { it.startsWith("$key:") } }
        .firstOrNull()
        ?.substringAfter(':')?.toIntOrNull()
    if (page == null && keepNewWhenOldMissing) return entries - stale.toSet()
    val kept = entries - stale.toSet() - ghosts.toSet()
    return if (page != null) kept + "$newKey:$page" else kept
}

/** Pure half of [SafRepository.anyLibraryTreeCovers]. */
// @spec LIB-EXT-012
internal fun anyLibraryTreeCovers(treeUris: Collection<String>, docUri: String): Boolean =
    treeUris.any { libraryTreeCoversDoc(it, docUri) }

/**
 * Whether the granted tree at [treeUri] covers the document at [docUri].
 * Two forms match, both boundary-anchored — never a bare string prefix, which
 * would conflate sibling trees (`Reports` vs `Reports2`, mirroring
 * DocumentDao.deleteByTreeUri's rationale):
 *  - a tree-form child URI: `<treeUri>/document/<docId>` — the `/document/`
 *    segment is the boundary;
 *  - a doc-form URI on the same authority whose decoded document id extends
 *    the tree's decoded id at a `/` boundary (`primary:Docs` covers
 *    `primary:Docs/x.pdf`); volume-root trees (`primary:`) bound at the `:`.
 */
// @spec LIB-EXT-012
internal fun libraryTreeCoversDoc(treeUri: String, docUri: String): Boolean {
    if (docUri.startsWith("$treeUri/document/")) return true
    val treeAuthority = DocumentLocations.authorityOf(treeUri) ?: return false
    if (treeAuthority != DocumentLocations.authorityOf(docUri)) return false
    val treeId = DocumentLocations.treeDocumentIdOf(treeUri) ?: return false
    val docId = DocumentLocations.documentIdOf(docUri) ?: return false
    if (treeId.endsWith(":")) return docId.length > treeId.length && docId.startsWith(treeId)
    return docId.startsWith("$treeId/")
}
