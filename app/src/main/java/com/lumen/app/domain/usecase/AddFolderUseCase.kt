package com.lumen.app.domain.usecase

import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.lumen.app.data.repository.LibraryRepository
import com.lumen.app.worker.IndexWorker
import javax.inject.Inject

class AddFolderUseCase @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val workManager: WorkManager,
) {
    suspend operator fun invoke(treeUri: Uri) {
        libraryRepository.addFolder(treeUri)
        workManager.enqueueUniqueWork(
            "index_$treeUri",
            ExistingWorkPolicy.REPLACE,
            IndexWorker.buildRequest(treeUri),
        )
    }
}
