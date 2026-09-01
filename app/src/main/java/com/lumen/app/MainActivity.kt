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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lumen.app.domain.model.PendingSearch
import com.lumen.app.launcher.RecentShortcutsUpdater
import com.lumen.app.launcher.SearchLaunch
import com.lumen.app.ui.navigation.LumenNavGraph
import com.lumen.app.ui.theme.LumenTheme
import com.lumen.app.ui.theme.resolveDarkTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    @Inject lateinit var recentShortcutsUpdater: RecentShortcutsUpdater
    private var externalPdfState: androidx.compose.runtime.MutableState<ExternalPdf?>? = null
    // Monotonic per-delivery counter: re-sending the same document is a fresh
    // delivery (distinct id → the graph re-pushes the viewer), while rotation /
    // process restore re-processes the sticky intent under the last id (the
    // graph's saved handled-guard suppresses it). Saved so ids never repeat
    // across recreation.
    private var externalPdfRequestId = 0L
    // Resolved once per delivery and carried across recreation: the display-name
    // query is a synchronous cross-process call that must not re-run on the
    // main thread for every rotation with a sticky VIEW intent. Non-null also
    // marks "this intent was accepted as a PDF".
    private var externalPdfName: String? = null

    /** An external VIEW-intent PDF: its URI plus the display name resolved from
     *  the provider while the access grant was live, tagged with the delivery id. */
    data class ExternalPdf(val uri: String, val displayName: String, val requestId: Long)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op: indexing proceeds regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        externalPdfRequestId = savedInstanceState?.getLong(KEY_EXTERNAL_PDF_REQUEST_ID) ?: 0L
        externalPdfName = savedInstanceState?.getString(KEY_EXTERNAL_PDF_NAME)
        if (savedInstanceState == null) routeSearchEntry(intent)
        // Dynamic "recent documents" shortcuts track library recency while the
        // activity is started — publishing from the background risks the
        // ShortcutManager rate limiter for no visible benefit.
        // @spec SEARCH-ENTRY-005
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                recentShortcutsUpdater.observe()
            }
        }
        val initialExternalPdf =
            processExternalIntent(intent, newDelivery = savedInstanceState == null)
        setContent {
            val externalPdf = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(initialExternalPdf)
            }
            externalPdfState = externalPdf
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
                            externalPdfUri = externalPdf.value?.uri,
                            externalPdfName = externalPdf.value?.displayName,
                            externalPdfRequestId = externalPdf.value?.requestId ?: 0L,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeSearchEntry(intent)
        externalPdfState?.value = processExternalIntent(intent, newDelivery = true)
    }

    // Launcher search entries (static shortcut, widget, PROCESS_TEXT) hand
    // their query to the search surface through PendingSearch. Only genuine
    // deliveries submit — the sticky intent re-processed on rotation or
    // process restore must not re-fire a search the user already dismissed.
    // @spec SEARCH-ENTRY-001, SEARCH-ENTRY-002, SEARCH-ENTRY-004
    private fun routeSearchEntry(intent: Intent?) {
        SearchLaunch.searchQueryOf(intent)?.let { PendingSearch.submit(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_EXTERNAL_PDF_REQUEST_ID, externalPdfRequestId)
        outState.putString(KEY_EXTERNAL_PDF_NAME, externalPdfName)
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

    // Resolves everything that needs the live access grant (display name,
    // persistable permission) at intent time — the grant may be single-use.
    // A new delivery takes a fresh request id; recreation reuses the last one
    // so the already-handled sticky intent is not pushed again.
    // @spec LIB-REC-004, NAV-012
    private fun processExternalIntent(intent: Intent?, newDelivery: Boolean): ExternalPdf? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val data: Uri = intent.data ?: return null
        val path = data.toString()
        val name: String
        if (newDelivery) {
            val mime = runCatching { contentResolver.getType(data) }.getOrNull().orEmpty()
            val looksLikePdf = mime.equals("application/pdf", ignoreCase = true) ||
                path.endsWith(".pdf", ignoreCase = true)
            if (!looksLikePdf) return null
            viewModel.takePersistableReadIfOffered(data, intent.flags)
            name = queryDisplayName(data)
                ?: data.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "PDF"
            externalPdfName = name
            externalPdfRequestId++
        } else {
            // Recreation with the sticky intent: it was validated and resolved
            // at delivery (the saved name doubles as the acceptance marker) —
            // no cross-process type/name queries on the main thread here.
            name = externalPdfName ?: return null
        }
        return ExternalPdf(uri = path, displayName = name, requestId = externalPdfRequestId)
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0).takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()

    private companion object {
        const val KEY_EXTERNAL_PDF_REQUEST_ID = "external_pdf_request_id"
        const val KEY_EXTERNAL_PDF_NAME = "external_pdf_name"
    }
}
