package com.lumen.app.eval

import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.domain.model.ScorerVariant
import com.lumen.app.domain.model.SearchFilters
import com.lumen.app.domain.model.SortOrder
import com.lumen.app.domain.usecase.SearchUseCase
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/**
 * Executes an evaluation run through the production search path — the same
 * `SearchUseCase` live search calls, RELEVANCE sort, one call per entry per
 * scorer variant. Debug-build tooling; nothing here is reachable in release.
 */
class EvalRunner @Inject constructor(
    private val searchUseCase: SearchUseCase,
    private val documentDao: DocumentDao,
) {

    // @spec SEARCH-EVAL-004
    suspend fun run(
        entries: List<EvalEntry>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<EntryResult> {
        // Unresolvable detection: one unfiltered filename query per run.
        // @spec SEARCH-EVAL-006
        val indexedNames = documentDao
            .indexedFilenameRows(filterByFolder = 0, treeUris = emptyList(), minIndexedAt = 0)
            .map { it.filename.lowercase() }
            .toSet()

        val variants = ScorerVariant.entries.toList()
        val results = mutableListOf<EntryResult>()
        entries.forEachIndexed { index, entry ->
            if (entry.expectedFile.lowercase() !in indexedNames) {
                variants.forEach { v ->
                    results += EntryResult(entry, v, EvalBucket.UNRESOLVABLE, rank = null, latencyMs = 0)
                }
            } else {
                for (variant in EvalScorer.variantOrderFor(index, variants)) {
                    val start = System.nanoTime()
                    try {
                        val output = searchUseCase(
                            entry.query,
                            SearchFilters(sortOrder = SortOrder.RELEVANCE),
                            variant,
                        )
                        val latency = (System.nanoTime() - start) / 1_000_000
                        val rank = EvalScorer.rankOfFirstHit(output.results, entry)
                        results += EntryResult(
                            entry = entry,
                            variant = variant,
                            bucket = if (rank != null) EvalBucket.HIT else EvalBucket.MISS,
                            rank = rank,
                            latencyMs = latency,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        results += EntryResult(entry, variant, EvalBucket.ERROR, rank = null, latencyMs = 0)
                    }
                }
            }
            onProgress(index + 1, entries.size)
        }
        return results
    }
}
