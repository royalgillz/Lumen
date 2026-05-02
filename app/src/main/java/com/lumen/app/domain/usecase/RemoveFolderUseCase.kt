package com.lumen.app.domain.usecase

import android.net.Uri
import androidx.work.WorkManager
import com.lumen.app.data.repository.LibraryRepository
import javax.inject.Inject

class RemoveFolderUseCase @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val workManager: WorkManager,
) {
    suspend operator fun invoke(treeUri: Uri) {
        workManager.cancelUniqueWork("index_$treeUri")
        libraryRepository.removeFolder(treeUri)
    }
}
