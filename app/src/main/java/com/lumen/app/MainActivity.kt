package com.lumen.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import com.lumen.app.ui.navigation.LumenNavGraph
import com.lumen.app.ui.theme.LumenTheme
import com.lumen.app.ui.theme.resolveDarkTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var externalPdfUriState: androidx.compose.runtime.MutableState<String?>? = null

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op: indexing proceeds regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        setContent {
            val externalPdfUri = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(extractPdfUri(intent))
            }
            externalPdfUriState = externalPdfUri
            // Nothing composes until theme, layout, and destination have loaded
            // from DataStore — a dark-theme user never sees a light first frame.
            // @spec SET-APPEAR-005, NAV-003
            val themeMode by viewModel.themeMode.collectAsState()
            val navConfig by viewModel.navConfig.collectAsState()
            themeMode?.let { mode ->
                LumenTheme(darkTheme = resolveDarkTheme(mode, isSystemInDarkTheme())) {
                    navConfig?.let { config ->
                        LumenNavGraph(
                            startDestination = config.startDestination,
                            layoutMode = config.layout,
                            externalPdfUri = externalPdfUri.value,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalPdfUriState?.value = extractPdfUri(intent)
    }

    /**
     * On Android 13+ the foreground-service indexing notification is suppressed unless the
     * user has granted POST_NOTIFICATIONS. Request it once; indexing still runs if declined.
     */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun extractPdfUri(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val data: Uri = intent.data ?: return null
        val mime = runCatching { contentResolver.getType(data) }.getOrNull().orEmpty()
        val path = data.toString()
        val looksLikePdf = mime.equals("application/pdf", ignoreCase = true) ||
            path.endsWith(".pdf", ignoreCase = true)
        return if (looksLikePdf) data.toString() else null
    }
}
