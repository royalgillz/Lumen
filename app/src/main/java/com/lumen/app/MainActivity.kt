package com.lumen.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.lumen.app.ui.navigation.LumenNavGraph
import com.lumen.app.ui.theme.LumenTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var externalPdfUriState: androidx.compose.runtime.MutableState<String?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val externalPdfUri = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(extractPdfUri(intent))
            }
            externalPdfUriState = externalPdfUri
            LumenTheme {
                val startDestination by viewModel.startDestination.collectAsState()
                startDestination?.let { dest ->
                    LumenNavGraph(
                        startDestination = dest,
                        externalPdfUri = externalPdfUri.value,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalPdfUriState?.value = extractPdfUri(intent)
    }

    private fun extractPdfUri(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val data: Uri = intent.data ?: return null
        val mime = contentResolver.getType(data).orEmpty()
        val path = data.toString()
        val looksLikePdf = mime.equals("application/pdf", ignoreCase = true) ||
            path.endsWith(".pdf", ignoreCase = true)
        return if (looksLikePdf) data.toString() else null
    }
}
