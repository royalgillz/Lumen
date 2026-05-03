package com.lumen.app.data.repository

import android.net.Uri
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.fs.SafRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepository @Inject constructor(
    private val safRepository: SafRepository,
    private val documentDao: DocumentDao,
) {
    val documents: Flow<List<DocumentEntity>> = documentDao.observeAll()
    val folders: Flow<Set<Uri>> = safRepository.folderUris
    val hasCompletedOnboarding: Flow<Boolean> = safRepository.hasCompletedOnboarding

    suspend fun addFolder(treeUri: Uri) = safRepository.addFolder(treeUri)

    suspend fun removeFolder(treeUri: Uri) {
        documentDao.deleteByTreeUri(treeUri.toString())
        safRepository.removeFolder(treeUri)
    }

    fun hasPermissionFor(uri: Uri): Boolean = safRepository.hasPersistedPermission(uri)

    suspend fun resetDocumentStatus(id: Long) =
        documentDao.updateStatus(id, DocumentEntity.STATUS_PENDING)
}
