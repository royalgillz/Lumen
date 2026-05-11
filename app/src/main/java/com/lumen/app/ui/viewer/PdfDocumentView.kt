package com.lumen.app.ui.viewer

import android.animation.ValueAnimator
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

    /** Per-(page, scaleBucket) bitmap cache. Sized in bytes. */
    private val bitmapCache: LruCache<CacheKey, Bitmap> =
        object : LruCache<CacheKey, Bitmap>(CACHE_MAX_BYTES) {
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
            val anchorPage = currentPageIndex()
            relayoutPages()
            jumpToPage(anchorPage, animate = false)
        }
        invalidate()
    }

    fun setHighlight(
        pageIndex: Int,
        rects: List<RectF>,
        pageWidthPts: Float,
        pageHeightPts: Float,
    ) {
        highlightPageIndex = pageIndex
        highlightRects = rects
        highlightPageWidthPts = pageWidthPts
        highlightPageHeightPts = pageHeightPts
        invalidate()
    }

    fun clearHighlight() {
        if (highlightPageIndex < 0 && highlightRects.isEmpty()) return
        highlightPageIndex = -1
        highlightRects = emptyList()
        invalidate()
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
        if (w > 0 && h > 0 && pageSizes.isNotEmpty()) {
            val anchorPage = currentPageIndex().coerceAtLeast(0)
            relayoutPages()
            jumpToPage(anchorPage, animate = false)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        flingJob?.let { removeCallbacks(it) }
        flingJob = null
        animator?.cancel()
        scope.cancel()
        bitmapCache.evictAll()
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
                // Prefetch this page's bitmap so a small scroll doesn't show blank.
                val key = CacheKey(i, scaleBucket)
                if (bitmapCache.get(key) == null) {
                    schedulePageRender(i, scaleBucket, zoom)
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
        for (rect in highlightRects) {
            tmpRect.set(
                docPageRect.left + rect.left * sx,
                docPageRect.top + rect.top * sy,
                docPageRect.left + rect.right * sx,
                docPageRect.top + rect.bottom * sy,
            )
            matrix.mapRect(tmpRect)
            canvas.drawRect(tmpRect, highlightPaint)
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
        // The view may not have been laid out yet — jumpToPage will be a no-op then,
        // and onSizeChanged will retry once we have a real width/height.
        if (width > 0 && height > 0) {
            jumpToPage(pendingInitialPage, animate = false)
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

        setMatrix(zoom = 1f, tx = 0f, ty = 0f)
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
        val renderScale = fitScale * scaleBucketToZoom(scaleBucket)
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

        /** ~80 MB upper bound on cached bitmaps; sized by byteCount, not entry count. */
        private const val CACHE_MAX_BYTES = 80 * 1024 * 1024
    }
}
