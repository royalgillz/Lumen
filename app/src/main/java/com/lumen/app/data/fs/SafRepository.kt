package com.lumen.app.data.fs

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
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
    }

    val folderUris: Flow<Set<Uri>> = dataStore.data.map { prefs ->
        prefs[KEY_FOLDER_URIS].orEmpty().map { Uri.parse(it) }.toSet()
    }

    /** Call immediately after the user grants a SAF tree URI from the folder picker. */
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
        } catch (_: SecurityException) {
            // Permission may have already been revoked by the OS
        }
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
}
