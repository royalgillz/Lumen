package com.lumen.app.domain.usecase

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.lumen.app.data.db.dao.RenameDao
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.fs.SafRepository
import com.lumen.app.data.fs.caseRenameTempFilename
import com.lumen.app.data.fs.isCaseOnlyRename
import com.lumen.app.data.fs.isInPlaceRename
import com.lumen.app.data.fs.isSameFilename
import com.lumen.app.data.fs.renameTargetFilename
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Renames the actual PDF on disk and migrates every URI-keyed store to the
 * provider-returned URI in one transaction (row id preserved — no re-index).
 * Failure leaves every store unchanged.
 */
class RenameDocumentFileUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val renameDao: RenameDao,
    private val safRepository: SafRepository,
) {

    sealed class Result {
        data class Renamed(val newUri: String, val newFilename: String) : Result()
        data class Failed(val message: String) : Result()
    }

    /** Provider support probe for gating the toggle (LIB-REN-004). */
    suspend fun supportsRename(docUri: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                Uri.parse(docUri),
                arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
                null, null, null,
            )?.use { c ->
                c.moveToFirst() &&
                    (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0
            } == true
        }.getOrDefault(false)
    }

    // @spec LIB-REN-002, LIB-REN-003, LIB-REN-006, LIB-REN-010, LIB-REN-011, LIB-REN-013
    suspend operator fun invoke(doc: DocumentEntity, typedName: String): Result =
        withContext(Dispatchers.IO) {
            val target = renameTargetFilename(typedName)
                ?: return@withContext Result.Failed("Enter a valid name.")

            // Same name: no provider call, but the file name still becomes the
            // one truth (custom title cleared, stale derivation reset).
            if (isSameFilename(typedName, doc.filename)) {
                runCatching { renameDao.applyOneTruthClear(doc.uri) }
                    .onFailure { return@withContext Result.Failed("Couldn't update the name.") }
                return@withContext Result.Renamed(doc.uri, doc.filename)
            }

            val returnedUri: Uri
            if (isCaseOnlyRename(typedName, doc.filename)) {
                // Case-insensitive storage rejects a direct case-only rename as
                // "already exists" — hop through a temporary name, rolling back
                // to the original name if the second step fails.
                // @spec LIB-REN-013
                val tempUri = try {
                    providerRename(
                        Uri.parse(doc.uri),
                        caseRenameTempFilename(target, System.currentTimeMillis()),
                    )
                } catch (e: Exception) {
                    return@withContext Result.Failed(renameFailureMessage(e))
                } ?: return@withContext Result.Failed(RENAME_NOT_CONFIRMED)
                returnedUri = try {
                    providerRename(tempUri, target) ?: run {
                        runCatching { providerRename(tempUri, doc.filename) }
                        return@withContext Result.Failed(RENAME_NOT_CONFIRMED)
                    }
                } catch (e: Exception) {
                    runCatching { providerRename(tempUri, doc.filename) }
                    return@withContext Result.Failed(renameFailureMessage(e))
                }
            } else {
                returnedUri = try {
                    providerRename(Uri.parse(doc.uri), target)
                } catch (e: Exception) {
                    return@withContext Result.Failed(renameFailureMessage(e))
                } ?: return@withContext Result.Failed(RENAME_NOT_CONFIRMED)
            }

            // The provider's word is truth: it may have deduplicated the name
            // ("name (1).pdf") — re-query rather than trusting the request.
            val newFilename = queryDisplayName(returnedUri) ?: target
            val newUri = returnedUri.toString()
            runCatching {
                if (isInPlaceRename(doc.uri, newUri)) {
                    // Stable-ID provider renamed in place: re-keying here would
                    // purge the document's own rows. Filename + one-truth
                    // clearing only; the reading position keeps its unchanged key.
                    // @spec LIB-REN-011
                    renameDao.applyInPlaceRename(doc.uri, newFilename)
                } else {
                    renameDao.applyFileRename(doc.uri, newUri, newFilename)
                    safRepository.rewriteLastPage(doc.uri, newUri)
                }
            }.onFailure {
                // The file IS renamed; the next scan reconciles the rows.
                return@withContext Result.Failed(
                    "Renamed the file, but updating Lumen's records failed — a re-index will fix it."
                )
            }
            Result.Renamed(newUri, newFilename)
        }

    private fun providerRename(uri: Uri, displayName: String): Uri? =
        DocumentsContract.renameDocument(context.contentResolver, uri, displayName)

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0).takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()

    private fun renameFailureMessage(e: Exception): String = when {
        e is SecurityException -> "Lumen doesn't have permission to rename files in this folder."
        e is UnsupportedOperationException -> "This storage location doesn't support renaming."
        // Providers throw IllegalStateException("Already exists /storage/…") —
        // the raw path must not reach the snackbar.
        // @spec LIB-REN-013
        e.message?.contains("already exists", ignoreCase = true) == true ->
            "A file with that name already exists."
        else -> "Couldn't rename the file" +
            (e.message?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ".")
    }

    private companion object {
        const val RENAME_NOT_CONFIRMED = "This storage location didn't confirm the rename."
    }
}
