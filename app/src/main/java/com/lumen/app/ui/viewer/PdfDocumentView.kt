package com.lumen.app.ui.viewer

import android.animation.ValueAnimator
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.SizeF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import androidx.collection.LruCache
import androidx.core.view.ViewCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Custom Android View that draws an entire PDF onto a single Canvas through a
 * single Matrix. Pan and pinch transform the matrix; pages are rendered as
 * Bitmaps via [MuPdfPageRenderer] and blitted at matrix-mapped page rects.
 *
 * The whole document is one continuous canvas, so panning across page edges
 * is just a translate — no per-page-pager handoff. Horizontal mode lays the
 * pages out side-by-side instead of stacked.
 */
class PdfDocumentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onPageChanged(currentPage: Int, totalPages: Int)
        fun onSingleTap()
        fun onExternalLinkTap(uri: String)
        fun onInternalLinkTap(pageIndex: Int)
        fun onZoomChanged(zoom: Float)
    }

    private var listener: Listener? = null
    private var renderer: MuPdfPageRenderer? = null

    private val matrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val inverseMatrix = Matrix()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var pageSizes: List<SizeF> = emptyList()
    /** doc-space y-offset (vertical) or x-offset (horizontal) for the top-left of each page. */
    private var pageOffsets: FloatArray = FloatArray(0)
    private var docContentWidth = 0f
    private var docContentHeight = 0f
    private var fitScale = 1f
    private var scrollHorizontal = false
    private var pendingInitialPage = 0
    private var pageCount = 0
    /** True once the matrix has been positioned for the current document, so resizes
     *  preserve the user's zoom/scroll instead of snapping back to the initial page. */
    private var hasPositioned = false

    /**
     * Device-aware cache budget. Bitmap pixels live in the native heap (API 26+),
     * which `largeHeap` does not bound, so a fixed ceiling is the bug pattern this
     * whole effort is removing. Derive from [ActivityManager.getLargeMemoryClass]
     * as a device-tier proxy (it governs Dalvik, not these native pixels, so it is
     * a tier signal — not a hard bound) and clamp to a sane band.
     */
    private val cacheMaxBytes: Int = run {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val largeClassMb = am?.largeMemoryClass ?: 96
        val quarterMb = (largeClassMb * 0.25f).toInt()
        quarterMb.coerceIn(CACHE_FLOOR_MB, CACHE_CEIL_MB) * 1024 * 1024
    }

    /**
     * Per-bitmap ceiling, derived from the cache budget so a single rendered page is
     * always smaller than the cache. Otherwise, on a small-heap device whose budget
     * floors at 24 MB, a 32 MB page bitmap would be evicted the instant it is
     * inserted — the render→evict→repeat thrash this cap exists to prevent. Capped
     * at 32 MB (≈ 8 MP ARGB_8888) on larger devices where the budget is generous.
     */
    private val maxBitmapBytes: Int =
        (cacheMaxBytes * 0.6f).toInt().coerceAtMost(32 * 1024 * 1024)

    /** Per-(page, scaleBucket) bitmap cache. Sized in bytes. */
    private val bitmapCache: LruCache<CacheKey, Bitmap> =
        object : LruCache<CacheKey, Bitmap>(cacheMaxBytes) {
            override fun sizeOf(key: CacheKey, value: Bitmap): Int = value.byteCount
            override fun entryRemoved(
                evicted: Boolean,
                key: CacheKey,
                oldValue: Bitmap,
                newValue: Bitmap?,
            ) {
                if (oldValue !== newValue) {
                    oldValue.recycle()
                }
            }
        }
    private val inFlightRenders = HashMap<CacheKey, Job>()

    /** Highlights for one specific page, in page-pt coordinates. */
    private var highlightPageIndex: Int = -1
    private var highlightRects: List<RectF> = emptyList()
    private var highlightPageWidthPts: Float = 0f
    private var highlightPageHeightPts: Float = 0f
    /** Index into [highlightRects] of the actively-focused occurrence, or -1. */
    private var highlightActiveIndex: Int = -1

    private val bgPaint = Paint().apply { color = 0xFF_141414.toInt() }
    private val pagePaint = Paint().apply { color = Color.WHITE; isAntiAlias = false }
    private val dividerPaint = Paint().apply {
        color = 0x33_FFFFFF.toInt()
        strokeWidth = context.resources.displayMetrics.density * 1f
    }
    private val highlightPaint = Paint().apply {
        color = Color.argb(89, 0xD4, 0xA2, 0x4C)
        style = Paint.Style.FILL
    }
    private val highlightActivePaint = Paint().apply {
        color = Color.argb(150, 0xD4, 0xA2, 0x4C)
        style = Paint.Style.FILL
    }
    private val highlightActiveStroke = Paint().apply {
        color = Color.argb(255, 0xB5, 0x7E, 0x1F)
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 1.5f
        isAntiAlias = true
    }
    private val bitmapPaint = Paint().apply { isFilterBitmap = true; isAntiAlias = false }

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector
    private val scroller = OverScroller(context, DecelerateInterpolator())
    private var flingJob: Runnable? = null
    private var animator: ValueAnimator? = null

    private var lastEmittedPage = -1
    private var lastEmittedZoom = -1f

    private val tmpRect = RectF()
    private val tmpPagePt = FloatArray(2)

    init {
        setBackgroundColor(0xFF_141414.toInt())
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setListener(l: Listener?) {
        listener = l
    }

    fun setRenderer(renderer: MuPdfPageRenderer?, initialPage: Int) {
        // Drop everything tied to the previous document.
        cancelAllRenders()
        bitmapCache.evictAll()
        flingJob?.let { removeCallbacks(it) }
        flingJob = null
        scroller.forceFinished(true)
        animator?.cancel()
        animator = null
        matrix.reset()
        hasPositioned = false

        this.renderer = renderer
        pendingInitialPage = initialPage
        pageSizes = emptyList()
        pageOffsets = FloatArray(0)
        docContentWidth = 0f
        docContentHeight = 0f
        pageCount = renderer?.pageCount ?: 0
        lastEmittedPage = -1
        lastEmittedZoom = -1f
        highlightPageIndex = -1
        highlightRects = emptyList()

        if (renderer != null) {
            scope.launch { loadPageSizes(renderer) }
        }
        invalidate()
    }

    fun setScrollHorizontal(horizontal: Boolean) {
        if (scrollHorizontal == horizontal) return
        scrollHorizontal = horizontal
        if (pageSizes.isNotEmpty() && width > 0 && height > 0) {
            val zoom = currentZoom().coerceIn(ZOOM_MIN, ZOOM_MAX)
            val anchorPage = currentPageIndex()
            relayoutPages()
            setMatrix(zoom, 0f, 0f)
            jumpToPage(anchorPage, animate = false)
        }
        invalidate()
    }

    fun setHighlight(
        pageIndex: Int,
        rects: List<RectF>,
        pageWidthPts: Float,
        pageHeightPts: Float,
        activeIndex: Int = -1,
    ) {
        highlightPageIndex = pageIndex
        highlightRects = rects
        highlightPageWidthPts = pageWidthPts
        highlightPageHeightPts = pageHeightPts
        highlightActiveIndex = if (rects.isEmpty()) -1 else activeIndex.coerceIn(-1, rects.size - 1)
        ensureActiveHighlightVisible()
        invalidate()
    }

    fun clearHighlight() {
        if (highlightPageIndex < 0 && highlightRects.isEmpty()) return
        highlightPageIndex = -1
        highlightRects = emptyList()
        highlightActiveIndex = -1
        invalidate()
    }

    /**
     * Pan (never zoom) so the active occurrence is brought into the viewport when
     * it is currently off-screen — mirrors the browser Ctrl+F "scroll to current
     * match" behaviour. Stays on the same page, so it never re-triggers a page
     * change / highlight reload.
     */
    private fun ensureActiveHighlightVisible() {
        val idx = highlightActiveIndex
        if (idx < 0 || idx >= highlightRects.size) return
        if (highlightPageIndex !in 0 until pageCount) return
        if (pageSizes.isEmpty() || width == 0 || height == 0) return
        val pageSize = pageSizes.getOrNull(highlightPageIndex) ?: return
        val docRect = docRectForPage(highlightPageIndex, pageSize)
        val sx = if (highlightPageWidthPts > 0f) pageSize.width / highlightPageWidthPts else 1f
        val sy = if (highlightPageHeightPts > 0f) pageSize.height / highlightPageHeightPts else 1f
        val r = highlightRects[idx]
        tmpRect.set(
            docRect.left + r.left * sx,
            docRect.top + r.top * sy,
            docRect.left + r.right * sx,
            docRect.top + r.bottom * sy,
        )
        matrix.mapRect(tmpRect)
        val fullyVisible = tmpRect.top >= 0f && tmpRect.bottom <= height &&
            tmpRect.left >= 0f && tmpRect.right <= width
        if (fullyVisible) return
        animator?.cancel()
        val dx = width / 2f - tmpRect.centerX()
        val dy = height / 2f - tmpRect.centerY()
        matrix.postTranslate(dx, dy)
        clampMatrix()
    }

    fun jumpToPage(index: Int, animate: Boolean = true) {
        if (index !in 0 until pageCount) return
        if (pageSizes.isEmpty() || pageOffsets.isEmpty() || width == 0 || height == 0) {
            pendingInitialPage = index
            return
        }
        val offset = pageOffsets[index]
        val zoom = currentZoom()
        val targetTx: Float
        val targetTy: Float
        if (scrollHorizontal) {
            val pageWidth = pageSizes[index].width
            val scaledPageW = pageWidth * fitScale * zoom
            targetTx = -(offset * fitScale * zoom) + max(0f, (width - scaledPageW) / 2f)
            targetTy = clampedTy(zoom, currentTy())
        } else {
            val pageWidth = pageSizes[index].width
            val scaledPageW = pageWidth * fitScale * zoom
            targetTx = max(0f, (width - scaledPageW) / 2f) - 0f
            targetTy = -(offset * fitScale * zoom)
        }
        if (animate) {
            animateMatrixTo(zoom, targetTx, targetTy)
        } else {
            setMatrix(zoom, targetTx, targetTy)
            clampMatrix()
            invalidate()
            maybeEmitPageChange()
        }
    }

    fun zoomBy(factor: Float, focusX: Float = width / 2f, focusY: Float = height / 2f) {
        val current = currentZoom()
        val target = (current * factor).coerceIn(ZOOM_MIN, ZOOM_MAX)
        if (abs(target - current) < 1e-3f) return
        animateZoom(target, focusX, focusY)
    }

    fun resetZoom() {
        if (abs(currentZoom() - 1f) < 1e-3f) return
        animateZoom(1f, width / 2f, height / 2f)
    }

    fun currentPageIndex(): Int {
        if (pageOffsets.isEmpty()) return 0
        val zoom = currentZoom()
        val centerDocCoord = if (scrollHorizontal) {
            (-currentTx() + width / 2f) / (fitScale * zoom)
        } else {
            (-currentTy() + height / 2f) / (fitScale * zoom)
        }
        // Find the page whose [offset, offset+size] contains centerDocCoord.
        var lo = 0
        var hi = pageOffsets.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (pageOffsets[mid] <= centerDocCoord) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun currentZoom(): Float {
        matrix.getValues(matrixValues)
        val rawScale = matrixValues[Matrix.MSCALE_X]
        if (fitScale <= 0f) return 1f
        return rawScale / fitScale
    }

    // ── View overrides ────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0 || pageSizes.isEmpty()) return
        if (!hasPositioned) {
            // First real layout: open at the requested page, zoom 1.
            relayoutPages()
            setMatrix(zoom = 1f, tx = 0f, ty = 0f)
            clampMatrix()
            jumpToPage(pendingInitialPage, animate = false)
            hasPositioned = true
        } else {
            // Later resizes (e.g. the toolbar auto-hiding) must NOT reset the user's
            // zoom — preserve the zoom and keep the same doc point centred.
            val zoom = currentZoom().coerceIn(ZOOM_MIN, ZOOM_MAX)
            val center = viewportCenterDoc()
            relayoutPages()
            restoreView(zoom, center[0], center[1])
            invalidate()
            maybeEmitPageChange()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerComponentCallbacks(memoryCallbacks)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runCatching { context.unregisterComponentCallbacks(memoryCallbacks) }
        flingJob?.let { removeCallbacks(it) }
        flingJob = null
        animator?.cancel()
        scope.cancel()
        bitmapCache.evictAll()
    }

    /**
     * Release the standing bitmap cache under real memory pressure, turning the
     * cache from an always-on allocation into one that collapses when the system
     * asks — more responsive than a static budget guess. Moderate pressure halves
     * it; critical pressure clears it.
     */
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            when {
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                    bitmapCache.evictAll()
                    invalidate()
                }
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                    bitmapCache.trimToSize(bitmapCache.maxSize() / 2)
                }
            }
        }

        @Deprecated("Required by ComponentCallbacks")
        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) { /* no-op */ }
        override fun onLowMemory() { bitmapCache.evictAll() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) {
            handled = gestureDetector.onTouchEvent(event) || handled
        }
        if (event.action == MotionEvent.ACTION_DOWN) {
            scroller.forceFinished(true)
            animator?.cancel()
        }
        return handled || super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pageSizes.isEmpty() || pageCount == 0 || fitScale <= 0f) {
            canvas.drawColor(bgPaint.color)
            return
        }
        canvas.drawColor(bgPaint.color)

        val zoom = currentZoom()
        val scaleBucket = scaleBucketFor(zoom)
        val visible = visiblePageRange()
        // Preload a small neighbourhood for smooth scroll.
        val firstPrefetch = max(0, visible.first - 1)
        val lastPrefetch = min(pageCount - 1, visible.last + 1)

        // With a single global render permit, a superseded render (wrong zoom bucket
        // or scrolled far off-screen) would otherwise hold up the renders the user is
        // actually waiting on. Cancel them so they release / never acquire the permit.
        cancelStaleRenders(firstPrefetch, lastPrefetch, scaleBucket)

        for (i in firstPrefetch..lastPrefetch) {
            val pageSize = pageSizes[i]
            val docRect = docRectForPage(i, pageSize)
            tmpRect.set(docRect)
            matrix.mapRect(tmpRect)
            if (i in visible) {
                // Page background (white) — also fills until bitmap loads.
                canvas.drawRect(tmpRect, pagePaint)
                val key = CacheKey(i, scaleBucket)
                val bitmap = bitmapCache.get(key)
                if (bitmap != null && !bitmap.isRecycled) {
                    canvas.drawBitmap(bitmap, null, tmpRect, bitmapPaint)
                } else {
                    // Try a fallback bitmap at any bucket so the user sees something
                    // while the correct-scale render lands.
                    drawAnyAvailableBitmap(canvas, i, tmpRect)
                    schedulePageRender(i, scaleBucket, zoom)
                }
                drawHighlightsForPage(canvas, i, docRect)
            } else {
                // Prefetch only a cheap base (bucket 0) bitmap for off-screen
                // neighbours. Prefetching at the current high zoom bucket would push
                // the working set past the cache, evicting the visible page's bitmap
                // and causing render→evict→repeat flicker.
                val key = CacheKey(i, 0)
                if (bitmapCache.get(key) == null) {
                    schedulePageRender(i, 0, 1f)
                }
            }
        }
    }

    private fun drawAnyAvailableBitmap(canvas: Canvas, pageIndex: Int, screenRect: RectF) {
        val snapshot = bitmapCache.snapshot()
        for ((k, v) in snapshot) {
            if (k.pageIndex == pageIndex && !v.isRecycled) {
                canvas.drawBitmap(v, null, screenRect, bitmapPaint)
                return
            }
        }
    }

    private fun drawHighlightsForPage(canvas: Canvas, pageIndex: Int, docPageRect: RectF) {
        if (pageIndex != highlightPageIndex) return
        if (highlightRects.isEmpty()) return
        val pageW = pageSizes.getOrNull(pageIndex)?.width ?: return
        val pageH = pageSizes.getOrNull(pageIndex)?.height ?: return
        // Map page-pt rects → doc-space (translate by page origin) → screen via matrix.
        // Highlights are reported in the same point space MuPDF used for rendering;
        // they may have been computed against a slightly different page size if the
        // PDF reports cropbox != mediabox. Scale to the renderer's bounds.
        val sx = if (highlightPageWidthPts > 0f) pageW / highlightPageWidthPts else 1f
        val sy = if (highlightPageHeightPts > 0f) pageH / highlightPageHeightPts else 1f
        for ((i, rect) in highlightRects.withIndex()) {
            tmpRect.set(
                docPageRect.left + rect.left * sx,
                docPageRect.top + rect.top * sy,
                docPageRect.left + rect.right * sx,
                docPageRect.top + rect.bottom * sy,
            )
            matrix.mapRect(tmpRect)
            if (i == highlightActiveIndex) {
                canvas.drawRect(tmpRect, highlightActivePaint)
                canvas.drawRect(tmpRect, highlightActiveStroke)
            } else {
                canvas.drawRect(tmpRect, highlightPaint)
            }
        }
    }

    // ── Layout & coordinate helpers ───────────────────────────────────────────

    private suspend fun loadPageSizes(renderer: MuPdfPageRenderer) {
        val sizes = ArrayList<SizeF>(renderer.pageCount)
        for (i in 0 until renderer.pageCount) {
            val size = renderer.pageSize(i) ?: SizeF(595f, 842f) // A4 fallback
            sizes.add(size)
        }
        pageSizes = sizes
        relayoutPages()
        // The view may not have been laid out yet — onSizeChanged will position once
        // we have a real width/height.
        if (width > 0 && height > 0 && !hasPositioned) {
            setMatrix(zoom = 1f, tx = 0f, ty = 0f)
            clampMatrix()
            jumpToPage(pendingInitialPage, animate = false)
            hasPositioned = true
        }
        invalidate()
    }

    private fun relayoutPages() {
        val n = pageSizes.size
        if (n == 0 || width == 0 || height == 0) return

        val gapPts = GAP_PTS
        val offsets = FloatArray(n)
        var maxWidthPts = 0f
        var maxHeightPts = 0f
        if (scrollHorizontal) {
            var cursor = 0f
            for (i in 0 until n) {
                offsets[i] = cursor
                val sz = pageSizes[i]
                cursor += sz.width + if (i < n - 1) gapPts else 0f
                maxWidthPts = max(maxWidthPts, sz.width)
                maxHeightPts = max(maxHeightPts, sz.height)
            }
            docContentWidth = cursor
            docContentHeight = maxHeightPts
        } else {
            var cursor = 0f
            for (i in 0 until n) {
                offsets[i] = cursor
                val sz = pageSizes[i]
                cursor += sz.height + if (i < n - 1) gapPts else 0f
                maxWidthPts = max(maxWidthPts, sz.width)
                maxHeightPts = max(maxHeightPts, sz.height)
            }
            docContentWidth = maxWidthPts
            docContentHeight = cursor
        }
        pageOffsets = offsets

        // fitScale: pick a base zoom such that "zoom = 1" fits the page snugly.
        fitScale = if (scrollHorizontal) {
            min(width.toFloat() / maxWidthPts, height.toFloat() / maxHeightPts)
        } else {
            width.toFloat() / maxWidthPts
        }
        if (fitScale <= 0f || !fitScale.isFinite()) fitScale = 1f
        // Layout only — callers position the matrix so zoom/scroll can be preserved
        // across resizes.
    }

    /** The doc-space point currently at the centre of the viewport. */
    private fun viewportCenterDoc(): FloatArray {
        matrix.getValues(matrixValues)
        val sx = matrixValues[Matrix.MSCALE_X]
        val tx = matrixValues[Matrix.MTRANS_X]
        val ty = matrixValues[Matrix.MTRANS_Y]
        if (sx <= 0f) return floatArrayOf(0f, 0f)
        return floatArrayOf((width / 2f - tx) / sx, (height / 2f - ty) / sx)
    }

    /** Restore [zoom] with the doc point ([docX], [docY]) centred in the viewport. */
    private fun restoreView(zoom: Float, docX: Float, docY: Float) {
        val scale = fitScale * zoom
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(width / 2f - docX * scale, height / 2f - docY * scale)
        clampMatrix()
    }

    private fun docRectForPage(pageIndex: Int, pageSize: SizeF): RectF {
        return if (scrollHorizontal) {
            val x = pageOffsets[pageIndex]
            val y = (docContentHeight - pageSize.height) / 2f
            RectF(x, y, x + pageSize.width, y + pageSize.height)
        } else {
            val y = pageOffsets[pageIndex]
            val x = (docContentWidth - pageSize.width) / 2f
            RectF(x, y, x + pageSize.width, y + pageSize.height)
        }
    }

    private fun visiblePageRange(): IntRange {
        if (pageOffsets.isEmpty()) return IntRange.EMPTY
        val zoom = currentZoom()
        val scale = fitScale * zoom
        if (scale <= 0f) return IntRange.EMPTY
        val viewportLo: Float
        val viewportHi: Float
        if (scrollHorizontal) {
            viewportLo = -currentTx() / scale
            viewportHi = (-currentTx() + width) / scale
        } else {
            viewportLo = -currentTy() / scale
            viewportHi = (-currentTy() + height) / scale
        }
        val first = findFirstPageOverlapping(viewportLo)
        val last = findLastPageOverlapping(viewportHi)
        if (first > last) return IntRange.EMPTY
        return first..last
    }

    private fun findFirstPageOverlapping(docCoord: Float): Int {
        // First page i such that pageOffsets[i] + pageSpan[i] >= docCoord.
        // Linear scan is fine — pageOffsets is small (rarely > 1000) and onDraw
        // already iterates a similar range.
        for (i in pageOffsets.indices) {
            val span = if (scrollHorizontal) pageSizes[i].width else pageSizes[i].height
            if (pageOffsets[i] + span >= docCoord) return i
        }
        return pageOffsets.size - 1
    }

    private fun findLastPageOverlapping(docCoord: Float): Int {
        for (i in pageOffsets.indices.reversed()) {
            if (pageOffsets[i] <= docCoord) return i
        }
        return 0
    }

    private fun currentTx(): Float {
        matrix.getValues(matrixValues)
        return matrixValues[Matrix.MTRANS_X]
    }

    private fun currentTy(): Float {
        matrix.getValues(matrixValues)
        return matrixValues[Matrix.MTRANS_Y]
    }

    private fun setMatrix(zoom: Float, tx: Float, ty: Float) {
        matrix.reset()
        matrix.postScale(fitScale * zoom, fitScale * zoom)
        matrix.postTranslate(tx, ty)
    }

    private fun clampMatrix() {
        matrix.getValues(matrixValues)
        val scale = matrixValues[Matrix.MSCALE_X]
        val tx = matrixValues[Matrix.MTRANS_X]
        val ty = matrixValues[Matrix.MTRANS_Y]
        val docPixW = docContentWidth * scale
        val docPixH = docContentHeight * scale
        val newTx = if (docPixW <= width) (width - docPixW) / 2f else tx.coerceIn(width - docPixW, 0f)
        val newTy = if (docPixH <= height) (height - docPixH) / 2f else ty.coerceIn(height - docPixH, 0f)
        if (newTx != tx || newTy != ty) {
            matrix.postTranslate(newTx - tx, newTy - ty)
        }
    }

    private fun clampedTy(zoom: Float, ty: Float): Float {
        val scale = fitScale * zoom
        val docPixH = docContentHeight * scale
        return if (docPixH <= height) (height - docPixH) / 2f else ty.coerceIn(height - docPixH, 0f)
    }

    // ── Gestures ──────────────────────────────────────────────────────────────

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            val current = currentZoom()
            val target = (current * factor).coerceIn(ZOOM_MIN, ZOOM_MAX)
            val effective = if (current == 0f) factor else target / current
            matrix.postScale(effective, effective, detector.focusX, detector.focusY)
            clampMatrix()
            invalidate()
            maybeEmitZoomChange()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            maybeEmitPageChange()
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            animator?.cancel()
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            matrix.postTranslate(-distanceX, -distanceY)
            clampMatrix()
            invalidate()
            maybeEmitPageChange()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            startFling(velocityX, velocityY)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val cur = currentZoom()
            val target = when {
                cur < ZOOM_MID - 0.05f -> ZOOM_MID
                cur < ZOOM_MAX - 0.05f -> ZOOM_MAX
                else -> ZOOM_MIN
            }
            animateZoom(target, e.x, e.y)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // Inverse-map the tap point into doc-space, find which page (if any) it's on,
            // and check for link hits at that page-point.
            if (!matrix.invert(inverseMatrix)) {
                listener?.onSingleTap()
                return true
            }
            tmpPagePt[0] = e.x; tmpPagePt[1] = e.y
            inverseMatrix.mapPoints(tmpPagePt)
            val docX = tmpPagePt[0]
            val docY = tmpPagePt[1]
            val hitPage = pageAtDocPoint(docX, docY)
            if (hitPage < 0) {
                listener?.onSingleTap()
                return true
            }
            val pageSize = pageSizes[hitPage]
            val pageRect = docRectForPage(hitPage, pageSize)
            val pageX = docX - pageRect.left
            val pageY = docY - pageRect.top
            // Link probe runs off-thread; deliver tap immediately so controls feel snappy
            // and dispatch the link result asynchronously.
            tryHandleLinkTap(hitPage, pageX, pageY)
            return true
        }
    }

    private fun pageAtDocPoint(x: Float, y: Float): Int {
        for (i in pageOffsets.indices) {
            val sz = pageSizes[i]
            val rect = docRectForPage(i, sz)
            if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) return i
        }
        return -1
    }

    private fun tryHandleLinkTap(pageIndex: Int, pageX: Float, pageY: Float) {
        val r = renderer ?: run { listener?.onSingleTap(); return }
        scope.launch {
            val links = r.linksForPage(pageIndex)
            val hit = links.firstOrNull { link ->
                val b = link.bounds
                pageX >= b.left && pageX <= b.right && pageY >= b.top && pageY <= b.bottom
            }
            if (hit == null) {
                listener?.onSingleTap()
                return@launch
            }
            if (hit.isExternal) {
                listener?.onExternalLinkTap(hit.uri)
            } else if (hit.destPage != null) {
                listener?.onInternalLinkTap(hit.destPage)
            } else {
                listener?.onSingleTap()
            }
        }
    }

    private fun startFling(velocityX: Float, velocityY: Float) {
        matrix.getValues(matrixValues)
        val tx = matrixValues[Matrix.MTRANS_X].toInt()
        val ty = matrixValues[Matrix.MTRANS_Y].toInt()
        val scale = matrixValues[Matrix.MSCALE_X]
        val docPixW = (docContentWidth * scale).toInt()
        val docPixH = (docContentHeight * scale).toInt()
        val minTx = if (docPixW <= width) tx else width - docPixW
        val maxTx = if (docPixW <= width) tx else 0
        val minTy = if (docPixH <= height) ty else height - docPixH
        val maxTy = if (docPixH <= height) ty else 0
        scroller.fling(tx, ty, velocityX.toInt(), velocityY.toInt(), minTx, maxTx, minTy, maxTy)
        val job = object : Runnable {
            override fun run() {
                if (scroller.computeScrollOffset()) {
                    matrix.getValues(matrixValues)
                    val curX = matrixValues[Matrix.MTRANS_X]
                    val curY = matrixValues[Matrix.MTRANS_Y]
                    matrix.postTranslate(scroller.currX - curX, scroller.currY - curY)
                    clampMatrix()
                    invalidate()
                    maybeEmitPageChange()
                    ViewCompat.postOnAnimation(this@PdfDocumentView, this)
                } else {
                    flingJob = null
                }
            }
        }
        flingJob = job
        ViewCompat.postOnAnimation(this, job)
    }

    private fun animateZoom(targetZoom: Float, focusX: Float, focusY: Float) {
        animator?.cancel()
        val startScale = (currentZoom() * fitScale)
        val endScale = (targetZoom * fitScale)
        if (abs(startScale - endScale) < 1e-3f) return
        animator = ValueAnimator.ofFloat(startScale, endScale).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            var prev = startScale
            addUpdateListener { v ->
                val s = v.animatedValue as Float
                val factor = s / prev
                prev = s
                matrix.postScale(factor, factor, focusX, focusY)
                clampMatrix()
                invalidate()
                maybeEmitZoomChange()
            }
            start()
        }
    }

    private fun animateMatrixTo(targetZoom: Float, targetTx: Float, targetTy: Float) {
        animator?.cancel()
        matrix.getValues(matrixValues)
        val startScale = matrixValues[Matrix.MSCALE_X]
        val endScale = fitScale * targetZoom
        val startTx = matrixValues[Matrix.MTRANS_X]
        val startTy = matrixValues[Matrix.MTRANS_Y]
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = DecelerateInterpolator()
            addUpdateListener { v ->
                val t = v.animatedValue as Float
                val s = startScale + (endScale - startScale) * t
                val tx = startTx + (targetTx - startTx) * t
                val ty = startTy + (targetTy - startTy) * t
                matrix.reset()
                matrix.postScale(s, s)
                matrix.postTranslate(tx, ty)
                clampMatrix()
                invalidate()
                maybeEmitZoomChange()
                maybeEmitPageChange()
            }
            start()
        }
    }

    // ── Page rendering scheduling ─────────────────────────────────────────────

    private fun schedulePageRender(pageIndex: Int, scaleBucket: Int, zoom: Float) {
        val r = renderer ?: return
        val key = CacheKey(pageIndex, scaleBucket)
        if (inFlightRenders.containsKey(key)) return
        if (bitmapCache.get(key) != null) return
        val renderScale = cappedRenderScale(pageIndex, fitScale * scaleBucketToZoom(scaleBucket))
        val job = scope.launch(Dispatchers.Main.immediate) {
            val bitmap = r.renderPage(pageIndex, renderScale) ?: run {
                inFlightRenders.remove(key)
                return@launch
            }
            inFlightRenders.remove(key)
            // Renderer might have been swapped while we were rendering; guard.
            if (this@PdfDocumentView.renderer !== r) {
                bitmap.recycle()
                return@launch
            }
            bitmapCache.put(key, bitmap)
            invalidate()
        }
        inFlightRenders[key] = job
    }

    private fun cancelAllRenders() {
        for ((_, job) in inFlightRenders) job.cancel()
        inFlightRenders.clear()
    }

    /**
     * Cancel in-flight renders the current frame no longer needs: any whose page is
     * outside [firstPage]..[lastPage], plus visible-range pages rendered at a stale
     * [currentBucket] (superseded by a zoom change). A render already inside its
     * native toPixmap can't be interrupted (MuPDF's toPixmap takes no abort cookie),
     * but cancelling frees coroutines still queued on the permit and discards stale
     * results, which is what keeps permit=1 responsive.
     */
    private fun cancelStaleRenders(firstPage: Int, lastPage: Int, currentBucket: Int) {
        if (inFlightRenders.isEmpty()) return
        val it = inFlightRenders.entries.iterator()
        while (it.hasNext()) {
            val (key, job) = it.next()
            val offscreen = key.pageIndex < firstPage || key.pageIndex > lastPage
            // Off-screen prefetch renders use bucket 0; don't treat those as stale.
            val staleBucket = key.scaleBucket != 0 && key.scaleBucket != currentBucket
            if (offscreen || staleBucket) {
                job.cancel()
                it.remove()
            }
        }
    }

    /**
     * Clamp a desired render scale so a single page bitmap never exceeds
     * [MAX_BITMAP_BYTES] or [MAX_DIM] on either axis. Without this, high zoom
     * buckets render multi-hundred-MB bitmaps that either OOM or — being larger
     * than the LRU cache — get evicted the instant they are inserted, producing
     * an endless render→evict→invalidate loop that looks like the page "snapping
     * back" to a low-resolution view. Capping keeps every render cacheable and
     * stable; extreme zoom is rendered a touch soft rather than thrashing.
     */
    private fun cappedRenderScale(pageIndex: Int, desired: Float): Float {
        val sz = pageSizes.getOrNull(pageIndex) ?: return desired
        val wPt = sz.width
        val hPt = sz.height
        if (wPt <= 0f || hPt <= 0f) return desired
        var s = desired
        val maxByDim = min(MAX_DIM / wPt, MAX_DIM / hPt)
        if (s > maxByDim) s = maxByDim
        val maxByBytes = sqrt(maxBitmapBytes.toDouble() / (4.0 * wPt * hPt)).toFloat()
        if (s > maxByBytes) s = maxByBytes
        return max(s, 0.1f)
    }

    private fun scaleBucketFor(zoom: Float): Int = when {
        zoom <= 1.25f -> 0
        zoom <= 2.5f -> 1
        zoom <= 5f -> 2
        else -> 3
    }

    private fun scaleBucketToZoom(bucket: Int): Float = when (bucket) {
        0 -> 1f
        1 -> 2f
        2 -> 4f
        else -> 8f
    }

    // ── Listener emit helpers ─────────────────────────────────────────────────

    private fun maybeEmitPageChange() {
        val p = currentPageIndex()
        if (p != lastEmittedPage) {
            lastEmittedPage = p
            listener?.onPageChanged(p, pageCount)
        }
    }

    private fun maybeEmitZoomChange() {
        val z = currentZoom()
        if (abs(z - lastEmittedZoom) > 0.01f) {
            lastEmittedZoom = z
            listener?.onZoomChanged(z)
        }
    }

    // ── Cache key ─────────────────────────────────────────────────────────────

    private data class CacheKey(val pageIndex: Int, val scaleBucket: Int)

    companion object {
        const val ZOOM_MIN = 1f
        const val ZOOM_MID = 3.25f
        const val ZOOM_MAX = 8f

        /** 1pt vertical gap between pages (vertical mode) or horizontal (horizontal mode). */
        private const val GAP_PTS = 6f

        /** Device-aware cache band, in MB (see [cacheMaxBytes]). Floor keeps a
         *  small-heap device usable; ceiling caps the standing native allocation. */
        private const val CACHE_FLOOR_MB = 24
        private const val CACHE_CEIL_MB = 96

        /** Hard cap on either bitmap dimension. */
        private const val MAX_DIM = 4096f
    }
}
