package com.lumen.app.ui.viewer

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** An axis-aligned rectangle in page-local points (origin at the page's
 *  top-left). Plain floats so the region math stays JVM-testable. */
data class PageRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isEmpty: Boolean get() = width <= 0f || height <= 0f
}

/**
 * Pure math behind sharp rendering of oversized pages. A whole-page bitmap is
 * capped by dimension and byte budgets; for extreme-aspect pages (GoodNotes-
 * style exports: one page, tens of thousands of points tall) that cap forces
 * the render scale far below reading resolution — the page is legible only as
 * a blur. Such pages get an additional sharp render of just the visible
 * region, which these functions detect, size, and validate.
 */
// @spec VIEW-BIG-001
object OversizedPageMath {

    /** How far the cap must degrade the wanted scale before the region path
     *  engages — normal pages must never take it. */
    const val OVERSIZE_FACTOR = 1.3f

    /** Fraction of the visible region added as margin on each side, so small
     *  pans stay inside the rendered region instead of re-rendering. */
    const val REGION_MARGIN_FRAC = 0.5f

    /**
     * The whole-page render scale after the bitmap caps: no axis over
     * [maxDimPx] and no bitmap over [maxBytes] (ARGB_8888, 4 bytes/px).
     */
    fun cappedScale(pageWPt: Float, pageHPt: Float, desired: Float, maxDimPx: Float, maxBytes: Int): Float {
        if (pageWPt <= 0f || pageHPt <= 0f) return desired
        var s = desired
        val maxByDim = min(maxDimPx / pageWPt, maxDimPx / pageHPt)
        if (s > maxByDim) s = maxByDim
        val maxByBytes = sqrt(maxBytes.toDouble() / (4.0 * pageWPt * pageHPt)).toFloat()
        if (s > maxByBytes) s = maxByBytes
        return s
    }

    /** True when the whole-page caps cost the page meaningful sharpness at the
     *  wanted scale — the trigger for region rendering. */
    fun isOversized(pageWPt: Float, pageHPt: Float, desired: Float, maxDimPx: Float, maxBytes: Int): Boolean {
        if (pageWPt <= 0f || pageHPt <= 0f || desired <= 0f) return false
        return desired > cappedScale(pageWPt, pageHPt, desired, maxDimPx, maxBytes) * OVERSIZE_FACTOR
    }

    /**
     * The region to render for [visible] (clamped to the page): the visible
     * rect grown by [REGION_MARGIN_FRAC] per side, then shrunk — margins
     * first, then a center-crop as the last resort — until the bitmap at
     * [scale] fits [maxBytes]. The result always contains as much of the
     * visible rect as the budget allows and never exceeds the page.
     */
    fun regionForViewport(
        pageWPt: Float,
        pageHPt: Float,
        visible: PageRegion,
        scale: Float,
        maxBytes: Int,
    ): PageRegion {
        val vis = clampToPage(visible, pageWPt, pageHPt)
        if (vis.isEmpty || scale <= 0f) return vis
        val budgetPx = maxBytes / 4.0
        var marginFrac = REGION_MARGIN_FRAC
        repeat(6) {
            val r = clampToPage(
                PageRegion(
                    vis.left - vis.width * marginFrac,
                    vis.top - vis.height * marginFrac,
                    vis.right + vis.width * marginFrac,
                    vis.bottom + vis.height * marginFrac,
                ),
                pageWPt, pageHPt,
            )
            val px = (r.width * scale).toDouble() * (r.height * scale)
            if (px <= budgetPx) return r
            marginFrac /= 2f
        }
        // Even the bare visible rect busts the budget (tiny budget or extreme
        // zoom): center-crop it to fit rather than fail.
        val visPx = (vis.width * scale).toDouble() * (vis.height * scale)
        if (visPx <= budgetPx) return vis
        val shrink = sqrt(budgetPx / visPx).toFloat()
        val cx = (vis.left + vis.right) / 2f
        val cy = (vis.top + vis.bottom) / 2f
        val hw = vis.width * shrink / 2f
        val hh = vis.height * shrink / 2f
        return clampToPage(PageRegion(cx - hw, cy - hh, cx + hw, cy + hh), pageWPt, pageHPt)
    }

    /** Whether a rendered [rendered] region still contains [needed] (with a
     *  hair of slack for float noise) — i.e. no re-render required yet. */
    fun covers(rendered: PageRegion, needed: PageRegion, slackPt: Float = 0.5f): Boolean =
        rendered.left <= needed.left + slackPt &&
            rendered.top <= needed.top + slackPt &&
            rendered.right >= needed.right - slackPt &&
            rendered.bottom >= needed.bottom - slackPt

    private fun clampToPage(r: PageRegion, pageWPt: Float, pageHPt: Float): PageRegion =
        PageRegion(
            max(0f, r.left),
            max(0f, r.top),
            min(pageWPt, r.right),
            min(pageHPt, r.bottom),
        )
}
