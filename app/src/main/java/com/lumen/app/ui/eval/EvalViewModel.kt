package com.lumen.app.ui.eval

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lumen.app.BuildConfig
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.data.fs.SafRepository
import com.lumen.app.eval.EntryResult
import com.lumen.app.eval.EvalBucket
import com.lumen.app.eval.EvalRunner
import com.lumen.app.eval.EvalScorer
import com.lumen.app.eval.EvalSetParser
import com.lumen.app.eval.ScorecardRow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debug-build only — the eval route is never registered in release. */
@HiltViewModel
class EvalViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val safRepository: SafRepository,
    private val evalRunner: EvalRunner,
    private val documentDao: DocumentDao,
    workManager: WorkManager,
) : ViewModel() {

    data class CorpusStamp(val indexedCount: Int, val newestIndexedAt: Long?)

    data class UiState(
        val evalSetUri: String? = null,
        val running: Boolean = false,
        val progress: Pair<Int, Int>? = null,
        val rows: List<ScorecardRow> = emptyList(),
        val unresolvable: Int = 0,
        val errors: Int = 0,
        val invalid: List<String> = emptyList(),
        val stamp: CorpusStamp? = null,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Index work running → scorecards are not comparable. @spec SEARCH-EVAL-008 */
    val indexingActive: StateFlow<Boolean> = workManager.getWorkInfosByTagFlow("index")
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(evalSetUri = safRepository.evalSetUri.first())
        }
    }

    // @spec SEARCH-EVAL-003
    fun onFilePicked(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            safRepository.saveEvalSetUri(uri.toString())
            _state.value = _state.value.copy(evalSetUri = uri.toString(), message = null)
        }
    }

    fun run() {
        val uriString = _state.value.evalSetUri ?: return
        if (_state.value.running) return
        viewModelScope.launch {
            _state.value = _state.value.copy(running = true, message = null, rows = emptyList())
            try {
                // Cap the read at 1 MB — a mis-picked PDF must not land in heap.
                // @spec SEARCH-EVAL-002
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(Uri.parse(uriString))?.use { stream ->
                        val bytes = stream.readBytes()
                        if (bytes.size > 1_048_576) null else bytes.decodeToString()
                    }
                }
                if (text == null) {
                    _state.value = _state.value.copy(
                        running = false,
                        message = "Couldn't read the eval set (missing, too large, or access lost) — pick it again.",
                    )
                    return@launch
                }
                val parsed = EvalSetParser.parse(text)
                if (parsed.entries.isEmpty()) {
                    _state.value = _state.value.copy(
                        running = false,
                        invalid = parsed.invalid,
                        message = "No valid entries in the eval set.",
                    )
                    return@launch
                }
                val results = evalRunner.run(parsed.entries) { done, total ->
                    _state.value = _state.value.copy(progress = done to total)
                }
                _state.value = _state.value.copy(
                    running = false,
                    progress = null,
                    rows = EvalScorer.scorecard(results),
                    unresolvable = results.distinctEntries(EvalBucket.UNRESOLVABLE),
                    errors = results.distinctEntries(EvalBucket.ERROR),
                    invalid = parsed.invalid,
                    stamp = CorpusStamp(documentDao.countIndexed(), documentDao.maxIndexedAt()),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    running = false,
                    progress = null,
                    message = "Run failed: ${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
    }

    private fun List<EntryResult>.distinctEntries(bucket: EvalBucket): Int =
        filter { it.bucket == bucket }.distinctBy { it.entry }.size

    /** Deterministic plain-text scorecard. @spec SEARCH-EVAL-009 */
    fun exportText(): String {
        val s = _state.value
        val stampLine = s.stamp?.let { stamp ->
            val date = stamp.newestIndexedAt?.let {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(it))
            } ?: "never"
            "${stamp.indexedCount} docs indexed · newest $date"
        } ?: "no stamp"
        return buildString {
            appendLine("Lumen search eval · v${BuildConfig.VERSION_NAME} · $stampLine")
            if (s.unresolvable > 0) appendLine("${s.unresolvable} unresolvable entries excluded")
            if (s.errors > 0) appendLine("${s.errors} entries errored")
            appendLine()
            appendLine("variant | tag | n | @10 | @20 | MRR | med ms")
            for (row in s.rows.sortedWith(compareBy({ it.variant.name }, { it.tag != EvalScorer.OVERALL }, { it.tag }))) {
                appendLine(
                    "${row.variant.name} | ${row.tag} | ${row.resolved} | " +
                        "${row.hitsAt10} | ${row.hitsAt20} | " +
                        "%.3f".format(Locale.US, row.mrr) + " | ${row.medianLatencyMs}"
                )
            }
        }
    }
}
