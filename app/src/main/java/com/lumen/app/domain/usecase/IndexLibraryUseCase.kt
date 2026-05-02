package com.lumen.app.domain.usecase

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.lumen.app.data.fs.SafRepository
import com.lumen.app.worker.IndexWorker
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class IndexLibraryUseCase @Inject constructor(
    private val safRepository: SafRepository,
    private val workManager: WorkManager,
) {
    suspend operator fun invoke() {
        safRepository.folderUris.first().forEach { uri ->
            workManager.enqueueUniqueWork(
                "index_$uri",
                ExistingWorkPolicy.KEEP,
                IndexWorker.buildRequest(uri),
            )
        }
    }
}
