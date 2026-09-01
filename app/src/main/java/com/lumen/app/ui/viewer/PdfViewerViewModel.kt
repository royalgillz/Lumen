package com.lumen.app.ui.viewer

import android.app.Application
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumen.app.data.db.FtsQuerySanitizer
import com.lumen.app.data.db.dao.BookmarkDao
import com.lumen.app.data.db.dao.DocumentDao
import com.lumen.app.data.db.dao.DocumentTitleDao
import com.lumen.app.data.db.dao.ExternalOpenDao
import com.lumen.app.data.db.dao.PageDao
import com.lumen.app.data.db.entity.BookmarkEntity
import com.lumen.app.data.db.entity.DocumentEntity
import com.lumen.app.data.db.entity.ExternalOpenEntity
import com.lumen.app.data.fs.ExternalAccessOffer
import com.lumen.app.data.fs.ExternalAccessOffers
import com.lumen.app.data.fs.SafRepository
import com.lumen.app.data.ocr.OcrWordBoxes
import com.lumen.app.data.pdf.PdfHighlighter
import com.lumen.app.data.repository.SearchRepository
import com.lumen.app.data.text.NormalizedMatcher
import com.lumen.app.di.ApplicationScope
import com.lumen.app.domain.model.DocumentTitles
import com.lumen.app.domain.model.ExternalAccessFeature
import com.lumen.app.domain.usecase.IndexExternalDocumentUseCase
import com.lumen.app.domain.usecase.KeepExternalAccessUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Shared with the screen: the Failed state carrying exactly this message gets
 *  a "Keep access to this file" recovery action instead of a dead-end. */
// @spec VIEW-EXT-009
internal const val MSG_EXTERNAL_ACCESS_EXPIRED =
    "Access to this PDF expired — reopen it from the app it came from."

@HiltViewModel
class PdfViewerViewModel @Inject constructor(
    application: Application,
    private val pdfHighlighter: PdfHighlighter,
    private val searchRepository: SearchRepository,
    private val safRepository: SafRepository,
    private val pageDao: PageDao,
    private val documentDao: DocumentDao,
    private val documentTitleDao: DocumentTitleDao,
    private val externalOpenDao: ExternalOpenDao,
    private val bookmarkDao: BookmarkDao,
    private val indexExternalDocument: IndexExternalDocumentUseCase,
    private val keepExternalAccessUseCase: KeepExternalAccessUseCase,
    @ApplicationScope private val appScope: CoroutineScope,
) : AndroidViewModel(application) {

    private companion object {
        /** Upper bound on the match-page navigation list per in-document search.
         *  Bounds the page list + lazy compute fan-out, not an up-front scan. */
        private const val MAX_MATCH_PAGES = 1000
    }

    // ── Document lifecycle ────────────────────────────────────────────────────

    sealed class DocumentState {
        object Idle : DocumentState()
        object Loading : DocumentState()
        /** [uri] identifies which document [renderer] holds — a singleTop swap
         *  briefly recomposes the screen's new URI against the old Loaded state,
         *  and wiring the stale renderer to a new-document view corrupts
         *  per-document state (reading progress, saved page). */
        data class Loaded(val renderer: MuPdfPageRenderer, val uri: String) : DocumentState()
        /** [wrongPassword] is true when a password was supplied and rejected, so
         *  the prompt can say "incorrect" rather than silently reopening. */
        data class NeedsPassword(val wrongPassword: Boolean = false) : DocumentState()
        data class Failed(val message: String) : DocumentState()
    }

    private val _documentState = MutableStateFlow<DocumentState>(DocumentState.Idle)
    val documentState: StateFlow<DocumentState> = _documentState.asStateFlow()

    // Resolved display title (customTitle > derivedTitle > filename) for the
    // top bar; null until resolved or when the document has no library row.
    private val _displayTitle = MutableStateFlow<String?>(null)
    val displayTitle: StateFlow<String?> = _displayTitle.asStateFlow()

    // The open document's library row, when it has one — gates the overflow
    // menu's Details entry (external opens have no row, no Details).
    // @spec LIB-REN-007
    private val _libraryDocument = MutableStateFlow<DocumentEntity?>(null)
    val libraryDocument: StateFlow<DocumentEntity?> = _libraryDocument.asStateFlow()

    private var openJob: Job? = null
    // Guards every currentRenderer swap: an open job cancelled after the native
    // open completed must hand its renderer to close() instead of publishing it,
    // and the superseding path must see any renderer already published — the
    // lock makes "adopt or close" and "swap out and close" atomic, so exactly
    // one owner closes every renderer.
    private val rendererLock = Any()
    private var currentRenderer: MuPdfPageRenderer? = null
    // Serialises the external-open record (adopted-open path) against the
    // unrecorded-grant release (teardown paths): the release must never revoke
    // a grant whose recordOpen has just landed — both blocks run on appScope
    // and would otherwise race their DB checks against the insert.
    // Process-wide, not per-ViewModel: keep-access can also run from a recents
    // row in another package, and its writes must serialize with this session's
    // record/release for the same document.
    private val externalRecordMutex = com.lumen.app.domain.model.ExternalOpensGate.mutex
    private var lastOpenedUri: String? = null
    private var lastOpenedPassword: String? = null

    /** Display name from the screen's filename argument — for external opens it
     *  is the provider name MainActivity resolved while the grant was live. */
    private var lastDisplayName: String = ""

    /** URI of the open document as observable state, driving the bookmarks flow. */
    private val openedUri = MutableStateFlow<String?>(null)

    /**
     * The page saved from a previous session, captured once per document open
     * BEFORE any save from this session can overwrite it — reading it lazily from
     * the screen raced the first onPageChanged save, which on small documents won
     * and silently suppressed the resume snackbar.
     */
    var resumePage: Int? = null
        private set

    /** Page currently on screen; survives rotation so the recreated view can
     *  reopen where the user actually was, not at the nav-arg page. */
    var lastViewedPage: Int = -1
        private set

    fun openDocument(uriString: String, password: String? = null, displayName: String = "") {
        if (displayName.isNotBlank()) lastDisplayName = displayName
        openJob?.cancel()
        val parsedUri = try {
            Uri.parse(uriString)
        } catch (_: Throwable) {
            _documentState.value = DocumentState.Failed("Unable to open this PDF — the link is invalid.")
            return
        }
        // Same URI + same password + already-loaded → no-op
        if (uriString == lastOpenedUri && password == lastOpenedPassword &&
            _documentState.value is DocumentState.Loaded) {
            return
        }
        // Different document reusing this ViewModel (launchSingleTop replaces the
        // nav entry's arguments in place, e.g. an external VIEW intent while the
        // viewer is open): drop every per-document remnant, or the new PDF opens
        // at the old one's page with the old one's search state.
        if (lastOpenedUri != null && uriString != lastOpenedUri) {
            // The outgoing document may hold an unrecorded external grant.
            // @spec VIEW-EXT-001
            releaseUnrecordedExternalGrant()
            resumePage = null
            lastViewedPage = -1
            savePageJob?.cancel()
            resetSearch()
            // The offer banner belongs to the outgoing document.
            // @spec VIEW-EXT-003
            _externalAccess.value = null
        }
        lastOpenedUri = uriString
        lastOpenedPassword = password
        openedUri.value = uriString
        _documentState.value = DocumentState.Loading
        openJob = viewModelScope.launch(Dispatchers.IO) {
            if (resumePage == null) resumePage = safRepository.getLastPage(uriString) ?: -1
            // Drop any prior session (close() defers the native teardown off-thread).
            val prior = synchronized(rendererLock) {
                currentRenderer.also { currentRenderer = null }
            }
            prior?.close()
            when (val res = MuPdfPageRenderer.open(getApplication(), parsedUri, password)) {
                is MuPdfPageRenderer.OpenResult.Ok -> {
                    // open() returns a live renderer even when this job was
                    // cancelled mid-open (a newer open superseded it); adopt it
                    // under the lock or close it here — never both, never neither.
                    val adopted = synchronized(rendererLock) {
                        if (isActive) {
                            currentRenderer = res.renderer
                            true
                        } else {
                            false
                        }
                    }
                    if (!adopted) {
                        res.renderer.close()
                        return@launch
                    }
                    _documentState.value = DocumentState.Loaded(res.renderer, uriString)
                    // User recency: once per genuine open (the same-document guard
                    // upstream suppresses rotation re-opens). Library documents
                    // update their row; a URI with no row is an external
                    // VIEW-intent open and is recorded in external_opens, with
                    // grants released for anything pruned past the cap.
                    // @spec LIB-REC-001
                    appScope.launch {
                        runCatching {
                            externalRecordMutex.withLock {
                                val now = System.currentTimeMillis()
                                val uriStr = parsedUri.toString()
                                val updated = documentDao.markOpened(uriStr, now)
                                if (updated == 0) {
                                    // A VIEW intent can carry the tree-less document
                                    // form of a file the library stores tree-form —
                                    // exact-string markOpened misses it. Match by
                                    // provider identity (authority + document id) and
                                    // bump the library row instead of double-recording
                                    // the same file as an external open.
                                    // @spec VIEW-EXT-002
                                    val libraryUri = libraryUriForSameDocument(uriStr)
                                    // Reconciliation requires the covering tree's
                                    // grant to be ALIVE: library rows survive
                                    // tree-grant loss, and releasing the doc-form
                                    // grant on the strength of a dead tree would
                                    // destroy the one access path that works —
                                    // including a grant the user kept seconds ago.
                                    // @spec LIB-EXT-018
                                    val treeAlive = libraryUri != null &&
                                        runCatching { safRepository.anyLiveLibraryTreeCovers(uriStr) }
                                            .getOrDefault(false)
                                    if (libraryUri != null && treeAlive) {
                                        documentDao.markOpened(libraryUri, now)
                                        // Library access flows through the live tree
                                        // grant; a doc-form grant taken at intent
                                        // time is unreferenced — release it
                                        // (exact-URI match, tree grants live under
                                        // different URIs).
                                        // @spec LIB-REC-007
                                        safRepository.releasePersistedRead(uriStr)
                                        // Reconcile the document's external era: a
                                        // stale ephemeral doc-form row double-lists
                                        // every search hit, and a stale external
                                        // recents row double-lists recency while
                                        // claiming the grant just released.
                                        // @spec VIEW-EXT-002
                                        documentDao.getByUri(uriStr)?.let { doc ->
                                            if (doc.ephemeralExpiresAt != null) documentDao.delete(doc.id)
                                        }
                                        externalOpenDao.delete(uriStr)
                                    } else {
                                        // No library row — or covered on paper only
                                        // (dead tree grant): the external era stays,
                                        // and the doc-form grant with it. The
                                        // library row's own recency still bumps so
                                        // the surfaces agree the doc was read.
                                        if (libraryUri != null) documentDao.markOpened(libraryUri, now)
                                        recordExternalOpen(parsedUri, uriStr, now)
                                    }
                                }
                            }
                        }
                    }
                    // Top-bar title: resolved from the title store by URI; the
                    // screen falls back to its filename argument (which keeps
                    // serving file operations) when no row exists.
                    // @spec SEARCH-UI-006
                    viewModelScope.launch {
                        runCatching {
                            val uriStr = parsedUri.toString()
                            val doc = documentDao.getByUri(uriStr)
                            _libraryDocument.value = doc
                            _displayTitle.value = doc?.let {
                                DocumentTitles.displayTitle(
                                    documentTitleDao.getTitle(uriStr),
                                    it.derivedTitle,
                                    it.filename,
                                )
                            }
                        }
                    }
                }
                MuPdfPageRenderer.OpenResult.NeedsPassword -> {
                    // A superseded open (open() is NonCancellable, so a cancelled
                    // job still receives its result) must not overwrite the
                    // successor document's state with a stale prompt — same guard
                    // the Ok branch applies at adoption.
                    if (isActive) {
                        _documentState.value =
                            DocumentState.NeedsPassword(wrongPassword = !password.isNullOrEmpty())
                    }
                }
                is MuPdfPageRenderer.OpenResult.Error -> {
                    // Access loss on an external recents entry (revoked grant,
                    // vanished file — never a parse or memory failure on a
                    // readable file) splits on the row's persisted flag: a
                    // persisted row self-heals by deletion (a held grant that
                    // fails means the file itself is gone), a transient row is
                    // kept and marked expired so it renders honestly instead of
                    // silently vanishing. The dead grant — if one is somehow
                    // held — is released either way (exact-URI match; library
                    // tree grants live under different URIs).
                    // @spec LIB-REC-005, LIB-EXT-002
                    val cause = res.cause
                    // Read before the row action below can delete/mark it: an
                    // external doc's access loss gets an external explanation —
                    // the default permission text prescribes library-folder
                    // surgery that cannot apply to a document with no folder.
                    // @spec LIB-EXT-002
                    val externalRow = if (cause != null && isAccessLoss(cause)) {
                        runCatching { externalOpenDao.getByUri(uriString) }.getOrNull()
                    } else {
                        null
                    }
                    // An orphan ephemeral doc (row pruned/self-healed away while
                    // its index rows linger) is still an EXTERNAL access loss:
                    // it must get the recoverable expired message, never the
                    // library-folder surgery advice.
                    // @spec LIB-EXT-019
                    val orphanEphemeral = externalRow == null && cause != null &&
                        isAccessLoss(cause) &&
                        runCatching {
                            documentDao.getByUri(uriString)?.ephemeralExpiresAt != null
                        }.getOrDefault(false)
                    if (cause != null && isAccessLoss(cause)) {
                        appScope.launch {
                            runCatching {
                                // Same mutex as record: the row read + write must
                                // not interleave with a concurrent recordOpen or
                                // grant release for this document.
                                externalRecordMutex.withLock {
                                    when (accessLossRowAction(externalOpenDao.getByUri(uriString))) {
                                        AccessLossRowAction.DELETE_ROW ->
                                            externalOpenDao.delete(uriString)
                                        AccessLossRowAction.MARK_EXPIRED ->
                                            externalOpenDao.markAccessLost(uriString)
                                        AccessLossRowAction.NONE -> Unit
                                    }
                                    // A row that just proved unopenable must stop
                                    // being offered by search — drop its ephemeral
                                    // index rows with the row/mark (a MARK_EXPIRED
                                    // doc re-indexes on a successful keep-access).
                                    // @spec LIB-EXT-019
                                    documentDao.getByUri(uriString)?.let { doc ->
                                        if (doc.ephemeralExpiresAt != null) documentDao.delete(doc.id)
                                    }
                                    // @spec LIB-REC-007
                                    safRepository.releasePersistedRead(uriString)
                                }
                            }
                        }
                    }
                    // Guarded like NeedsPassword; the row eviction above still
                    // runs — access loss for the old document stays true even
                    // when a newer open superseded this one.
                    if (isActive) {
                        _documentState.value = DocumentState.Failed(
                            if (externalRow != null || orphanEphemeral) {
                                MSG_EXTERNAL_ACCESS_EXPIRED
                            } else {
                                userMessageForOpenFailure(res.cause)
                            }
                        )
                    }
                }
            }
        }
    }

    fun retry() {
        val uri = lastOpenedUri ?: return
        openDocument(uri, lastOpenedPassword)
    }

    override fun onCleared() {
        super.onCleared()
        openJob?.cancel()
        // @spec VIEW-EXT-001
        releaseUnrecordedExternalGrant()
        val prior = synchronized(rendererLock) {
            currentRenderer.also { currentRenderer = null }
        }
        prior?.close()
    }

    /**
     * A read grant persisted at VIEW-intent time is only discoverable through an
     * external_opens row; when this session ends without one (password prompt
     * abandoned, non-access-loss open failure), release the grant so it cannot
     * outlive every privacy control. Document grants for the exact external URI
     * only — library rows are skipped, and tree grants live under different URIs.
     */
    // @spec VIEW-EXT-001
    private fun releaseUnrecordedExternalGrant() {
        if (_documentState.value is DocumentState.Loaded) return
        // Adoption is the atomic "a record is coming" signal: an adopted open's
        // appScope block records the open (or releases the grant itself on the
        // library-row path) even after this ViewModel clears — Loading with an
        // adopted renderer means the Loaded write simply hasn't landed yet.
        if (synchronized(rendererLock) { currentRenderer != null }) return
        val uriStr = lastOpenedUri ?: return
        appScope.launch {
            runCatching {
                externalRecordMutex.withLock {
                    if (documentDao.getByUri(uriStr) != null) return@withLock
                    if (uriStr in externalOpenDao.getAllUris()) return@withLock
                    safRepository.releasePersistedRead(uriStr)
                }
            }
        }
    }

    /** Library row URI naming the same provider document as [externalUri] (same
     *  authority + SAF document id), or null. Tree-form library URIs and the
     *  tree-less VIEW-intent form never match by string, so compare identity. */
    private suspend fun libraryUriForSameDocument(externalUri: String): String? {
        val authority = SafDocumentUris.authorityOf(externalUri) ?: return null
        val docId = SafDocumentUris.documentIdOf(externalUri) ?: return null
        for (tree in documentDao.distinctTreeUris()) {
            if (SafDocumentUris.authorityOf(tree) != authority) continue
            for (row in documentDao.idUrisByTreeUri(tree)) {
                if (SafDocumentUris.documentIdOf(row.uri) == docId) return row.uri
            }
        }
        return null
    }

    // ── External document access (offers + keep-access) ───────────────────────

    /** Capability facts + the decided offer for the CURRENT external document;
     *  null for library documents and while nothing external is open. */
    data class ExternalAccessUi(
        val docUri: String,
        val displayName: String,
        /** Lumen holds a persistable read grant for this exact URI (record-time check). */
        val persisted: Boolean,
        /** A library folder covers the document — the folder pass owns it. */
        val coveredByLibrary: Boolean,
        val offer: ExternalAccessOffer,
    )

    private val _externalAccess = MutableStateFlow<ExternalAccessUi?>(null)
    val externalAccess: StateFlow<ExternalAccessUi?> = _externalAccess.asStateFlow()

    /** One-shot keep-access outcomes for the screen (snackbar / nav re-point).
     *  [newDisplayName] is the stored display name of the picked document, for
     *  the Rekeyed/DifferentDocument nav-entry replacement. */
    data class KeepAccessEvent(
        val result: KeepExternalAccessUseCase.Result,
        val newDisplayName: String? = null,
    )

    private val _keepAccessEvents = MutableSharedFlow<KeepAccessEvent>(extraBufferCapacity = 4)
    val keepAccessEvents: SharedFlow<KeepAccessEvent> = _keepAccessEvents.asSharedFlow()

    /**
     * Records an external open (no library row matched) and evaluates the
     * make-permanent offer, all inside the caller's [externalRecordMutex] hold.
     * The row's `persisted` flag is a record-time check of the resolver's
     * persisted-permission list — never inferred from intent flags. A persisted
     * open feeds the rolling-TTL ephemeral indexer, except when a library
     * folder covers the document (its index rows are the folder pass's job —
     * a second, ephemeral row would double-list the file in search).
     */
    // @spec LIB-EXT-001, LIB-EXT-016, VIEW-EXT-003
    private suspend fun recordExternalOpen(parsedUri: Uri, uriStr: String, now: Long) {
        val displayName = lastDisplayName.ifBlank { "PDF" }
        // @spec LIB-EXT-001
        val persisted = safRepository.hasPersistedRead(parsedUri)
        val pruned = externalOpenDao.recordOpen(
            ExternalOpenEntity(
                docUri = uriStr,
                displayName = displayName,
                lastOpenedAt = now,
                persisted = persisted,
            )
        )
        // @spec LIB-REC-007
        pruned.forEach { prunedUri ->
            safRepository.releasePersistedRead(prunedUri)
            // A pruned doc's ephemeral index rows must go with its row and
            // grant: search must not keep offering a document that can no
            // longer open (the tap would dead-end until the TTL purge).
            // @spec LIB-EXT-019
            documentDao.getByUri(prunedUri)?.let { doc ->
                if (doc.ephemeralExpiresAt != null) documentDao.delete(doc.id)
            }
        }
        val covered = runCatching { safRepository.anyLiveLibraryTreeCovers(uriStr) }.getOrDefault(false)
        // Ephemeral indexing ships disabled with the offer surface in v1.2 —
        // without the offers, persisted grants are rare and the TTL behaviors
        // would be an untestable release surface.
        // @spec LIB-EXT-021
        if (ExternalAccessFeature.OFFERS_ENABLED && persisted && !covered) {
            // Rolling TTL: an indexed ephemeral row just gets its 7-day window
            // restamped; anything else enqueues the single-document pass.
            // @spec LIB-EXT-016
            runCatching {
                indexExternalDocument(uriStr, grantPersists = true, displayName = displayName)
            }
        }
        if (covered) {
            // A covering folder arrived after this doc was ephemeral-indexed:
            // drop the doc-form row now rather than waiting out its TTL — the
            // tree-form row serves search, and two rows double-list every hit.
            // @spec VIEW-EXT-002
            runCatching {
                documentDao.getByUri(uriStr)?.let { doc ->
                    if (doc.ephemeralExpiresAt != null) documentDao.delete(doc.id)
                }
            }
        }
        val dismissed = externalOpenDao.getByUri(uriStr)?.offerDismissed ?: false
        // v1.2 ships the offer surface off; the state still flows (persisted /
        // covered stay useful) with the offer forced to NONE.
        // @spec LIB-EXT-021
        val offer = if (!ExternalAccessFeature.OFFERS_ENABLED) {
            ExternalAccessOffer.NONE
        } else {
            ExternalAccessOffers.decide(
                docUri = uriStr,
                apiLevel = Build.VERSION.SDK_INT,
                hasPersistedGrant = persisted,
                coveredByLibraryTree = covered,
                offerDismissed = dismissed,
            )
        }
        // Evaluated once per open; guarded so a superseding document swap's
        // banner never shows the outgoing document's offer.
        if (openedUri.value == uriStr) {
            _externalAccess.value = ExternalAccessUi(uriStr, displayName, persisted, covered, offer)
        }
    }

    /** The user dismissed the make-permanent banner: hides it now and persists
     *  the dismissal — the offer appears at most once per document, never nags. */
    // @spec LIB-EXT-005, VIEW-EXT-004
    fun dismissExternalOffer() {
        val ui = _externalAccess.value ?: return
        _externalAccess.value = ui.copy(offer = ExternalAccessOffer.NONE)
        appScope.launch {
            runCatching { externalOpenDao.setOfferDismissed(ui.docUri) }
        }
    }

    /**
     * A library folder was just added from an access offer (ADD_FOLDER or the
     * Lumen-pdfs flow's final step): when the new grant covers the current
     * external document, any ephemeral doc-form index row is DROPPED — the
     * enqueued folder pass indexes the file under its tree-form URI, and a
     * promoted doc-form row would live forever as a folderless duplicate no
     * cleanup path can reach (bookmarks and reading positions are URI-keyed
     * outside documents and survive the drop). Adding an unrelated folder
     * changes nothing — the document's access is still transient and the
     * offer stands.
     */
    // @spec LIB-EXT-003, VIEW-EXT-005
    fun onExternalFolderAdded() {
        val ui = _externalAccess.value ?: return
        appScope.launch {
            runCatching {
                val covered = safRepository.anyLiveLibraryTreeCovers(ui.docUri)
                if (covered) {
                    documentDao.getByUri(ui.docUri)?.let { doc ->
                        if (doc.ephemeralExpiresAt != null) documentDao.delete(doc.id)
                    }
                }
                if (openedUri.value == ui.docUri) {
                    _externalAccess.value = ui.copy(
                        coveredByLibrary = covered,
                        offer = if (covered) ExternalAccessOffer.NONE else ui.offer,
                    )
                }
            }
        }
    }

    /**
     * ACTION_OPEN_DOCUMENT result of the keep-file offer. [original] is the
     * banner's document URI captured when the picker was LAUNCHED — reading
     * the current document at result time would misclassify the pick after a
     * document swap while the picker was up. The use case takes the
     * process-wide external-opens gate INTERNALLY (it is also invoked from
     * the recents row in another package) — do not wrap the call in
     * [externalRecordMutex]: they are the same non-reentrant lock, and nesting
     * would deadlock. Outcomes land in [keepAccessEvents]; a Kept outcome also
     * flips the banner state to persisted/no-offer.
     */
    // @spec LIB-EXT-017, VIEW-EXT-006
    fun onKeepAccessPicked(original: String, pickedUri: Uri) {
        appScope.launch {
            val result = keepExternalAccessUseCase(original, pickedUri)
            val newUri = when (result) {
                is KeepExternalAccessUseCase.Result.Rekeyed -> result.newUri
                is KeepExternalAccessUseCase.Result.DifferentDocument -> result.newUri
                else -> null
            }
            val newName = newUri?.let {
                runCatching { externalOpenDao.getByUri(it)?.displayName }.getOrNull()
            }
            val ui = _externalAccess.value
            if (result is KeepExternalAccessUseCase.Result.Kept && ui != null && ui.docUri == original) {
                _externalAccess.value = ui.copy(persisted = true, offer = ExternalAccessOffer.NONE)
            }
            // A document swap while the picker was up orphans the outcome —
            // never re-point the viewer at a URI the user has moved past.
            if (lastOpenedUri == original) {
                _keepAccessEvents.tryEmit(KeepAccessEvent(result, newName))
            }
        }
    }

    private fun userMessageForOpenFailure(cause: Throwable?): String {
        if (cause is OutOfMemoryError) return OOM_MESSAGE
        val msg = cause?.message.orEmpty()
        if (msg.contains("Failed to allocate", ignoreCase = true) ||
            msg.contains("OutOfMemory", ignoreCase = true) ||
            msg.contains("OOM", ignoreCase = true)) return OOM_MESSAGE
        if (msg.contains("permission", ignoreCase = true)) {
            return "Permission denied.\nGo to Library, remove this folder, and re-add it to restore access."
        }
        if (msg.contains("FileNotFound", ignoreCase = true) || msg.contains("No such file", ignoreCase = true)) {
            return "File not found.\nThis PDF may have been moved or deleted."
        }
        return if (msg.isNotBlank()) msg else "Unable to open this PDF."
    }

    // ── In-document search (match-page model, lazy per-page highlights) ────────
    //
    // Navigation is page-based: the FTS index gives the matching pages cheaply
    // (size-independent), and the user steps page to page. Highlight rects are
    // produced per page on demand:
    //   • OCR/scanned page → from word boxes stored at index time (a free DB read);
    //     always drawn, no size check.
    //   • Text-layer page  → MuPDF structured text for that one page only, computed
    //     lazily when the page is visited, gated, with a per-page OOM catch. If a
    //     single heavy page fails it yields empty rects and search still navigates
    //     there; it just doesn't paint on that page.
    // No whole-file size gate exists: search and OCR highlights work at any size.

    private val _matchPages = MutableStateFlow<List<Int>>(emptyList())
    val matchPages: StateFlow<List<Int>> = _matchPages.asStateFlow()

    /** 1-based ordinal of the active occurrence across the whole document; 0 = none. */
    private val _occurrenceOrdinal = MutableStateFlow(0)
    val occurrenceOrdinal: StateFlow<Int> = _occurrenceOrdinal.asStateFlow()

    /** Total occurrences known so far. Grows as the background gated counting pass
     *  finishes scanning match pages; settles at the true total. */
    private val _occurrenceTotal = MutableStateFlow(0)
    val occurrenceTotal: StateFlow<Int> = _occurrenceTotal.asStateFlow()

    /** Page to bring into view for the active occurrence; -1 when none. */
    private val _activePage = MutableStateFlow(-1)
    val activePage: StateFlow<Int> = _activePage.asStateFlow()

    /** Rect on the active page to emphasise; -1 when none / not yet known. */
    private val _activeRectIndexOnPage = MutableStateFlow(-1)
    val activeRectIndexOnPage: StateFlow<Int> = _activeRectIndexOnPage.asStateFlow()

    /**
     * One-shot "scroll the view to this page" requests, emitted only by explicit
     * search actions (a new search landing, next/prev navigation). Events rather
     * than state: re-collecting state after rotation would re-jump the view to the
     * match page and lose the reader's restored position.
     */
    private val _scrollToPage = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val scrollToPage: SharedFlow<Int> = _scrollToPage.asSharedFlow()

    /** Lazily-populated per-page highlight rects, keyed by page index. An entry with
     *  empty rects means "computed, nothing to draw" and is not recomputed. */
    private val _pageHighlights = MutableStateFlow<Map<Int, PdfHighlighter.PageHighlights>>(emptyMap())
    val pageHighlights: StateFlow<Map<Int, PdfHighlighter.PageHighlights>> = _pageHighlights.asStateFlow()

    private var searchJob: Job? = null
    private var countJob: Job? = null
    private var navJob: Job? = null
    // Serialises mutations to the rect cache + derived counts across the count pass,
    // navigation, and on-demand page computes.
    private val cacheMutex = kotlinx.coroutines.sync.Mutex()

    // Context retained so a page's rects can be computed lazily on visit.
    private var searchDocUri: String = ""
    private var searchUri: Uri? = null
    private var searchKeyword: String = ""
    private var searchPassword: String? = null
    // Bumped whenever the search context changes; a highlight compute started
    // under an older generation must never write into the new search's cache.
    @Volatile private var searchGeneration = 0

    /**
     * Run an in-document search for [keyword]. Always proceeds regardless of file
     * size. Lists matching pages from the FTS index, positions the active occurrence,
     * and kicks off a background gated pass that counts occurrences per page so the
     * total settles to its true value. [preferredPage]/[preferredOccurrence] let a
     * global-search result open on the exact occurrence the user tapped.
     */
    fun runInDocumentSearch(
        docUri: String,
        keyword: String,
        password: String? = null,
        preferredPage: Int? = null,
        preferredOccurrence: Int = 0,
    ) {
        val trimmed = keyword.trim()
        // Same live search (e.g. the screen recomposing after rotation): keep the
        // existing match state instead of resetting and re-jumping to a match page.
        if (docUri == searchDocUri && trimmed == searchKeyword && _matchPages.value.isNotEmpty()) {
            return
        }
        searchJob?.cancel()
        countJob?.cancel()
        navJob?.cancel()
        if (trimmed.length < 2) {
            resetSearch()
            return
        }
        val parsedUri = runCatching { Uri.parse(docUri) }.getOrNull() ?: run { resetSearch(); return }
        // Same gate as content search: no token with ≥ 2 normalized chars → no
        // search (sanitize returns null for those and for zero-token queries).
        // @spec VIEW-MATCH-005
        val sanitized = FtsQuerySanitizer.sanitize(trimmed) ?: run { resetSearch(); return }
        searchDocUri = docUri
        searchUri = parsedUri
        searchKeyword = trimmed
        searchPassword = password
        searchGeneration++
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val pages = searchRepository.searchPagesInDocument(sanitized, docUri).take(MAX_MATCH_PAGES)
            _pageHighlights.value = emptyMap()
            _occurrenceTotal.value = 0
            _occurrenceOrdinal.value = 0
            _matchPages.value = pages
            if (pages.isEmpty()) {
                // The FTS index knows nothing about documents that were never
                // indexed — opened directly via a VIEW intent (e.g. a mail
                // attachment), or encrypted/errored at index time. For those, fall
                // back to scanning the open document's text page by page.
                val scanned = if (isSearchableInIndex(docUri)) emptyList() else scanOpenDocument(trimmed)
                if (scanned.isEmpty()) {
                    _activePage.value = -1
                    _activeRectIndexOnPage.value = -1
                    return@launch
                }
                recompute()
                startCountPass()
                return@launch
            }
            // Land on the first occurrence at/after the preferred page (wrapping),
            // so a page that matched in FTS but has no drawable rects is skipped.
            val startPage = preferredPage?.takeIf { it in pages } ?: pages.first()
            val startIdx = pages.indexOf(startPage)
            var landed = false
            for (off in 0 until pages.size) {
                val p = pages[(startIdx + off) % pages.size]
                val n = rectsFor(p).rects.size
                if (n > 0) {
                    _activePage.value = p
                    _activeRectIndexOnPage.value =
                        if (p == startPage) preferredOccurrence.coerceIn(0, n - 1) else 0
                    _scrollToPage.tryEmit(p)
                    landed = true
                    break
                }
            }
            if (!landed) {
                // Pages matched but none yielded rects (e.g. all heavy text pages that
                // OOM-skipped). Still position on the start page so search "works".
                _activePage.value = startPage
                _activeRectIndexOnPage.value = -1
                _scrollToPage.tryEmit(startPage)
            }
            recompute()
            startCountPass()
        }
    }

    /** True while the fallback page-by-page scan is running, so the UI can show
     *  "Searching…" instead of a premature "No matches". */
    private val _isScanningFallback = MutableStateFlow(false)
    val isScanningFallback: StateFlow<Boolean> = _isScanningFallback.asStateFlow()

    /** A document only trusts the FTS index for search when it finished indexing;
     *  missing or encrypted/errored documents get the fallback scan. */
    private suspend fun isSearchableInIndex(docUri: String): Boolean {
        val doc = runCatching { documentDao.getByUri(docUri) }.getOrNull()
        return doc != null && doc.status == DocumentEntity.STATUS_INDEXED
    }

    /**
     * Fallback in-document search for unindexed documents: walk every page's
     * MuPDF structured text with phrase-aware AND matching (mirroring FTS
     * semantics — every query phrase somewhere on the page, quoted phrases
     * adjacent), so page membership agrees with the phrase-aware highlighter
     * and the viewer never jumps to a "match" page with zero highlights.
     * Publishes match pages progressively and lands on the first match as
     * soon as it's found, so the reader isn't waiting on a full scan of a
     * large file. Scanned pages without a text layer yield no words and
     * simply never match.
     */
    // @spec VIEW-MATCH-003
    private suspend fun scanOpenDocument(keyword: String): List<Int> {
        val renderer = currentRenderer ?: return emptyList()
        if (NormalizedMatcher.phrasesOf(keyword).isEmpty()) return emptyList()
        val found = mutableListOf<Int>()
        _isScanningFallback.value = true
        try {
            for (p in 0 until renderer.pageCount) {
                currentCoroutineContext().ensureActive()
                val words = runCatching { renderer.wordsForPage(p) }.getOrNull().orEmpty()
                if (words.isEmpty()) continue
                // Words joined in reading order: adjacency inside a quoted
                // phrase spans word boundaries exactly as the highlighter's
                // page-text matching sees them.
                val pageText = words.joinToString(" ") { it.text }
                if (!NormalizedMatcher.matchesAllPhrases(pageText, keyword)) continue
                found += p
                _matchPages.value = found.toList()
                if (found.size == 1) {
                    // Land immediately on the first hit while the scan continues.
                    val n = rectsFor(p).rects.size
                    _activePage.value = p
                    _activeRectIndexOnPage.value = if (n > 0) 0 else -1
                    _scrollToPage.tryEmit(p)
                    recompute()
                }
                if (found.size >= MAX_MATCH_PAGES) break
            }
        } finally {
            _isScanningFallback.value = false
        }
        return found
    }

    /**
     * Ensure rects for [page] are computed and cached, if [page] is a match page and
     * not already done. Called when a match page is scrolled to, so its highlights
     * paint even if the background count pass hasn't reached it yet.
     */
    fun ensurePageHighlights(page: Int) {
        if (page < 0 || page !in _matchPages.value) return
        if (_pageHighlights.value.containsKey(page)) return
        if (searchKeyword.length < 2) return
        viewModelScope.launch(Dispatchers.IO) { rectsFor(page) }
    }

    /**
     * Background gated pass: compute rects for every match page, one at a time, so the
     * occurrence total settles. Each page goes through [rectsFor], which shares the
     * single MuPDF render permit and catches per-page OOM — safe on huge files.
     */
    private fun startCountPass() {
        countJob?.cancel()
        countJob = viewModelScope.launch(Dispatchers.IO) {
            for (p in _matchPages.value) {
                if (!isActive) break
                rectsFor(p)
            }
        }
    }

    /**
     * Return rects for [page], computing and caching on first request. Cache writes
     * and the derived occurrence counts are guarded by [cacheMutex] so the count
     * pass, navigation, and scroll-driven computes don't race.
     */
    private suspend fun rectsFor(page: Int): PdfHighlighter.PageHighlights {
        _pageHighlights.value[page]?.let { return it }
        val uri = searchUri ?: return PdfHighlighter.PageHighlights(page, emptyList(), 0f, 0f)
        val keyword = searchKeyword
        if (keyword.length < 2) return PdfHighlighter.PageHighlights(page, emptyList(), 0f, 0f)
        val generation = searchGeneration
        val computed = computePageHighlights(uri, page, keyword, searchPassword)
        return cacheMutex.withLock {
            // A compute cancelled mid-flight, or started for a superseded search,
            // must not poison the new search's cleared cache (the uncontended
            // mutex fast path acquires without a cancellation check, and empty
            // entries are never recomputed).
            currentCoroutineContext().ensureActive()
            if (generation != searchGeneration) return@withLock computed
            _pageHighlights.value[page] ?: run {
                _pageHighlights.value = _pageHighlights.value + (page to computed)
                recomputeCounts()
                computed
            }
        }
    }

    /** OCR page → DB boxes (free); else text-layer page → lazy MuPDF rects,
     *  preferring the viewer's already-open document over a per-page reopen. */
    private suspend fun computePageHighlights(
        uri: Uri,
        page: Int,
        keyword: String,
        password: String?,
    ): PdfHighlighter.PageHighlights {
        ocrHighlightsForPage(page, keyword)?.let { return it }
        // Cancellation is rethrown, never swallowed: a cancelled compute falling
        // through the fallbacks would yield stale-keyword or empty rects.
        currentRenderer?.let { renderer ->
            try {
                renderer.withDocumentGated { doc ->
                    pdfHighlighter.findOnPageInDocument(doc, page, keyword)
                }?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
        // Renderer closed / not ready — fall back to a one-shot open.
        return try {
            pdfHighlighter.findOnPage(uri, page, keyword, password)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            PdfHighlighter.PageHighlights(page, emptyList(), 0f, 0f)
        }
    }

    /**
     * Highlight rects for a single scanned page from its stored OCR word boxes.
     * Returns null when [page] is not an OCR page (caller falls back to text);
     * returns a (possibly empty-rect) result when it is, so we never recompute it.
     */
    // @spec VIEW-MATCH-002
    private suspend fun ocrHighlightsForPage(page: Int, keyword: String): PdfHighlighter.PageHighlights? {
        val renderer = currentRenderer ?: return null
        // Token-based like the text-layer path: OCR boxes are per word, so a
        // multi-word query can never match a single box — match each token instead,
        // through the same NormalizedMatcher ("f1" must find a box reading "F-1").
        val needles = NormalizedMatcher.tokensOf(keyword)
        if (needles.isEmpty()) return null
        val rows = try {
            pageDao.ocrWordBoxes(searchDocUri, listOf(page))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        val row = rows?.firstOrNull() ?: return null
        val size = renderer.pageSize(page) ?: return PdfHighlighter.PageHighlights(page, emptyList(), 0f, 0f)
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return PdfHighlighter.PageHighlights(page, emptyList(), 0f, 0f)
        val rects = OcrWordBoxes.decode(row.wordBoxesJson)
            .filter { box -> needles.any { NormalizedMatcher.findMatches(box.text, it).isNotEmpty() } }
            .map { RectF(it.left * w, it.top * h, it.right * w, it.bottom * h) }
        return PdfHighlighter.PageHighlights(page, rects, w, h)
    }

    /**
     * Words for long-press selection on [page]: structured text when the page has
     * a text layer, else the OCR word boxes stored at index time — so selection
     * also works on scanned pages. Keyed by the open document's own URI (not the
     * search context, which only exists during an in-document search).
     */
    suspend fun wordsForPage(page: Int): List<MuPdfPageRenderer.WordBox> {
        val renderer = currentRenderer
        val textWords = renderer?.let { runCatching { it.wordsForPage(page) }.getOrNull() }.orEmpty()
        if (textWords.isNotEmpty()) return textWords
        val docUri = lastOpenedUri ?: return emptyList()
        val rows = runCatching { pageDao.ocrWordBoxes(docUri, listOf(page)) }.getOrNull()
        val row = rows?.firstOrNull() ?: return emptyList()
        val size = renderer?.pageSize(page) ?: return emptyList()
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return emptyList()
        val boxes = OcrWordBoxes.decode(row.wordBoxesJson)
        // OCR boxes carry no structural line order — derive it, so multi-word
        // selection unions per line and copies with line breaks on scans too.
        // @spec VIEW-SEL-008
        val lines = TextSelectionModel.assignLineIndices(
            boxes.map { TextSelectionModel.Box(it.left, it.top, it.right, it.bottom) },
        )
        return boxes.mapIndexed { i, box ->
            MuPdfPageRenderer.WordBox(
                box.text,
                RectF(box.left * w, box.top * h, box.right * w, box.bottom * h),
                lines[i],
            )
        }
    }

    fun nextOccurrence() = step(+1)

    fun prevOccurrence() = step(-1)

    /**
     * Step one occurrence in [dir] (+1 next, -1 prev). Walks rects within the active
     * page first, then advances to the adjacent match page that has rects (computing
     * lazily, wrapping around), skipping match pages with no drawable rects.
     */
    private fun step(dir: Int) {
        val pages = _matchPages.value
        if (pages.isEmpty()) return
        navJob?.cancel()
        // IO, not Main: an uncached page runs native structured-text extraction here.
        navJob = viewModelScope.launch(Dispatchers.IO) {
            val page = _activePage.value
            val curRects = if (page >= 0) rectsFor(page).rects.size else 0
            val target = _activeRectIndexOnPage.value + dir
            if (page >= 0 && target in 0 until curRects) {
                _activeRectIndexOnPage.value = target
                recompute()
                return@launch
            }
            val base = pages.indexOf(page).coerceAtLeast(0)
            for (off in 1..pages.size) {
                val p = pages[((base + dir * off) % pages.size + pages.size) % pages.size]
                val n = rectsFor(p).rects.size
                if (n > 0) {
                    _activePage.value = p
                    _activeRectIndexOnPage.value = if (dir > 0) 0 else n - 1
                    _scrollToPage.tryEmit(p)
                    recompute()
                    return@launch
                }
            }
        }
    }

    fun clearSearch() = resetSearch()

    /** Recompute the displayed ordinal/total from the rects cached so far. Total only
     *  counts computed pages, so it grows as the count pass fills in. */
    private fun recomputeCounts() {
        val pages = _matchPages.value
        val hl = _pageHighlights.value
        var total = 0
        for (p in pages) total += hl[p]?.rects?.size ?: 0
        val active = _activePage.value
        val rect = _activeRectIndexOnPage.value
        val ordinal = if (active < 0 || rect < 0) {
            0
        } else {
            var before = 0
            for (p in pages) {
                if (p == active) break
                before += hl[p]?.rects?.size ?: 0
            }
            before + rect + 1
        }
        _occurrenceOrdinal.value = ordinal
        _occurrenceTotal.value = maxOf(total, ordinal)
    }

    private suspend fun recompute() = cacheMutex.withLock { recomputeCounts() }

    private fun resetSearch() {
        searchJob?.cancel()
        countJob?.cancel()
        navJob?.cancel()
        searchGeneration++
        _matchPages.value = emptyList()
        _pageHighlights.value = emptyMap()
        _occurrenceOrdinal.value = 0
        _occurrenceTotal.value = 0
        _activePage.value = -1
        _activeRectIndexOnPage.value = -1
        searchDocUri = ""
        searchUri = null
        searchKeyword = ""
        searchPassword = null
    }

    // ── Scroll mode ───────────────────────────────────────────────────────────

    val scrollHorizontal: StateFlow<Boolean> = safRepository.viewerScrollHorizontal
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleScrollMode() {
        viewModelScope.launch {
            safRepository.setViewerScrollHorizontal(!scrollHorizontal.value)
        }
    }

    // ── Bookmarks ─────────────────────────────────────────────────────────────

    sealed class BookmarkEvent {
        data class Added(val id: Long, val page: Int) : BookmarkEvent()
        data class Removed(val page: Int) : BookmarkEvent()
    }

    /** One-shot add/remove notifications driving the snackbar ("Bookmarked p. N —
     *  Add note"). Events, not state: replaying on rotation would re-show it. */
    private val _bookmarkEvents = MutableSharedFlow<BookmarkEvent>(extraBufferCapacity = 4)
    val bookmarkEvents: SharedFlow<BookmarkEvent> = _bookmarkEvents.asSharedFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val bookmarks: StateFlow<List<BookmarkEntity>> = openedUri
        .flatMapLatest { uri ->
            if (uri == null) flowOf(emptyList()) else bookmarkDao.observeForDocument(uri)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Toggle a bookmark on [page]: adds when absent, removes when present. */
    fun toggleBookmark(page: Int) {
        val uri = lastOpenedUri ?: return
        if (page < 0) return
        viewModelScope.launch(Dispatchers.IO) {
            val existing = bookmarkDao.getByPage(uri, page)
            if (existing != null) {
                bookmarkDao.deleteById(existing.id)
                _bookmarkEvents.tryEmit(BookmarkEvent.Removed(page))
            } else {
                val id = bookmarkDao.insert(
                    BookmarkEntity(docUri = uri, pageNumber = page, createdAt = System.currentTimeMillis())
                )
                _bookmarkEvents.tryEmit(BookmarkEvent.Added(id, page))
            }
        }
    }

    fun setBookmarkNote(id: Long, note: String) {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkDao.updateNote(id, note.trim().ifBlank { null })
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { bookmarkDao.deleteById(id) }
    }

    // ── Reading progress ──────────────────────────────────────────────────────

    private var savePageJob: Job? = null

    /**
     * Record the page currently on screen. The DataStore write is debounced (a
     * fling emits every page it crosses) and runs on the application scope so the
     * final position lands even if the user exits the viewer inside the window.
     */
    fun noteCurrentPage(uri: String, page: Int) {
        lastViewedPage = page
        savePageJob?.cancel()
        savePageJob = appScope.launch {
            delay(400)
            safRepository.saveLastPage(uri, page)
        }
    }
}

private const val OOM_MESSAGE =
    "This PDF requires too much memory to display. Close other apps and try again, " +
        "or view it on a PC if it contains many high-resolution scanned pages."
