package com.lumen.app.ui.eval

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.lumen.app.eval.EvalExport
import com.lumen.app.eval.EvalScorer
import java.util.Locale

/**
 * Debug-build search-quality scorecard. Never registered in release builds.
 */
// @spec SEARCH-EVAL-001
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvalScreen(
    onBack: () -> Unit,
    viewModel: EvalViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val indexing by viewModel.indexingActive.collectAsState()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.onFilePicked(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search eval") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                if (state.evalSetUri == null) "No eval set picked." else "Eval set ready.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "*/*")) }) {
                    Text(if (state.evalSetUri == null) "Pick eval set" else "Change file")
                }
                Spacer(Modifier.width(12.dp))
                Button(onClick = { viewModel.run() }, enabled = state.evalSetUri != null && !state.running) {
                    Text("Run")
                }
            }

            if (indexing) {
                Spacer(Modifier.height(12.dp))
                Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.small) {
                    Text(
                        "Indexing is running — results won't be comparable to other runs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }

            if (state.running) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                    val p = state.progress
                    Text(if (p != null) "Query ${p.first} / ${p.second}" else "Starting…")
                }
            }

            state.message?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            if (state.rows.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                // Corpus stamp: two runs compare only when stamps match.
                // @spec SEARCH-EVAL-008
                state.stamp?.let { stamp ->
                    Text(
                        "Corpus: " + EvalExport.stampLine(stamp.indexedCount, stamp.newestIndexedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.unresolvable > 0 || state.errors > 0) {
                    Text(
                        "${state.unresolvable} unresolvable, ${state.errors} errored — excluded from rates",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "variant · tag · n · @10 · @20 · MRR · med ms",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
                for (row in state.rows.sortedWith(EvalExport.sortOrder())) {
                    Text(
                        "${row.variant.name} · ${row.tag} · ${row.resolved} · ${row.hitsAt10} · " +
                            "${row.hitsAt20} · " + "%.3f".format(Locale.US, row.mrr) +
                            " · ${row.medianLatencyMs}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(12.dp))
                // @spec SEARCH-EVAL-009
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, viewModel.exportText())
                            },
                            "Share scorecard",
                        )
                    )
                }) { Text("Share scorecard") }
            }

            if (state.invalid.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Invalid entries (${state.invalid.size}):",
                    style = MaterialTheme.typography.labelSmall,
                )
                state.invalid.forEach { reason ->
                    Text(reason, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Entries: {\"query\", \"expectedFile\", \"expectedPage\" (1-indexed, optional), \"tag\" (optional)}. " +
                    "This screen exists only in debug builds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
