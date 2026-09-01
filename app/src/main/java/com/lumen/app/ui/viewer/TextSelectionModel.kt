package com.lumen.app.ui.viewer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Word-range math for multi-word text selection on a single page, kept free of
 * Android types so it is JVM-testable. The [PdfDocumentView] owns the gesture
 * and draw wiring; this class owns which words are selected, how a handle drag
 * moves the range (including anchor swap when a handle is dragged past its
 * counterpart), the per-line union boxes for the highlight overlay, and the
 * copied-text assembly.
 *
 * Words are in reading order (MuPDF structured-text order, or OCR boxes with
 * derived line indices); the selection is always a contiguous index range.
 * All coordinates are page points, origin at the page's top-left.
 */
// @spec VIEW-SEL-002, VIEW-SEL-005, VIEW-SEL-007
class TextSelectionModel(private val words: List<Word>) {

    data class Word(
        val text: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val line: Int,
    )

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** Inclusive selected range, in reading order. -1/-1 while empty. */
    var startIndex: Int = -1
        private set
    var endIndex: Int = -1
        private set

    val hasSelection: Boolean get() = startIndex in words.indices && endIndex in words.indices

    fun startWord(): Word? = words.getOrNull(startIndex)

    fun endWord(): Word? = words.getOrNull(endIndex)

    /**
     * Select the single word containing ([x], [y]) within [slop]; returns false
     * (leaving any existing selection untouched) when no word is hit.
     */
    fun selectWordAt(x: Float, y: Float, slop: Float): Boolean {
        val idx = words.indexOfFirst { w ->
            x >= w.left - slop && x <= w.right + slop && y >= w.top - slop && y <= w.bottom + slop
        }
        if (idx < 0) return false
        startIndex = idx
        endIndex = idx
        return true
    }

    /**
     * The word index a handle drag at ([x], [y]) should land on: the word under
     * the point when there is one, otherwise the nearest word on the nearest
     * line — so a drag past the line's end clamps to its last word, above the
     * first line clamps to word 0, and below the last line to the final word.
     */
    fun indexNear(x: Float, y: Float): Int {
        if (words.isEmpty()) return -1
        val direct = words.indexOfFirst { w ->
            x >= w.left && x <= w.right && y >= w.top && y <= w.bottom
        }
        if (direct >= 0) return direct
        var bestIdx = 0
        var bestLineDist = Float.MAX_VALUE
        // Nearest line first: vertical distance to the word's band, 0 when inside.
        for (w in words) {
            val d = when {
                y < w.top -> w.top - y
                y > w.bottom -> y - w.bottom
                else -> 0f
            }
            if (d < bestLineDist) bestLineDist = d
        }
        var bestXDist = Float.MAX_VALUE
        for ((i, w) in words.withIndex()) {
            val dLine = when {
                y < w.top -> w.top - y
                y > w.bottom -> y - w.bottom
                else -> 0f
            }
            if (dLine > bestLineDist + LINE_DIST_EPS) continue
            val dx = when {
                x < w.left -> w.left - x
                x > w.right -> x - w.right
                else -> 0f
            }
            if (dx < bestXDist) {
                bestXDist = dx
                bestIdx = i
            }
        }
        return bestIdx
    }

    /**
     * Move one handle of the selection to [index]. [draggingStart] says which
     * handle the finger holds; when it is dragged past its counterpart the
     * handles swap roles mid-drag (standard text-selection behaviour). Returns
     * whether the dragged handle is the start handle AFTER the move — the
     * caller keeps dragging with the returned role.
     */
    fun dragHandle(draggingStart: Boolean, index: Int): Boolean {
        if (!hasSelection || words.isEmpty()) return draggingStart
        val idx = index.coerceIn(0, words.size - 1)
        return if (draggingStart) {
            if (idx <= endIndex) {
                startIndex = idx
                true
            } else {
                startIndex = endIndex
                endIndex = idx
                false
            }
        } else {
            if (idx >= startIndex) {
                endIndex = idx
                false
            } else {
                endIndex = startIndex
                startIndex = idx
                true
            }
        }
    }

    /** Selected words' boxes unioned per source line, for the highlight overlay. */
    fun selectionBoxes(): List<Box> {
        if (!hasSelection) return emptyList()
        val boxes = ArrayList<Box>()
        var line = -1
        var left = 0f; var top = 0f; var right = 0f; var bottom = 0f
        var open = false
        for (i in startIndex..endIndex) {
            val w = words[i]
            if (!open || w.line != line) {
                if (open) boxes.add(Box(left, top, right, bottom))
                line = w.line
                left = w.left; top = w.top; right = w.right; bottom = w.bottom
                open = true
            } else {
                left = min(left, w.left)
                top = min(top, w.top)
                right = max(right, w.right)
                bottom = max(bottom, w.bottom)
            }
        }
        if (open) boxes.add(Box(left, top, right, bottom))
        return boxes
    }

    /**
     * The selected words joined in reading order: spaces within a source line,
     * a line break between lines.
     */
    fun selectedText(): String {
        if (!hasSelection) return ""
        val sb = StringBuilder()
        var line = -1
        for (i in startIndex..endIndex) {
            val w = words[i]
            if (sb.isNotEmpty()) {
                sb.append(if (w.line != line) '\n' else ' ')
            }
            line = w.line
            sb.append(w.text)
        }
        return sb.toString()
    }

    companion object {
        private const val LINE_DIST_EPS = 0.5f

        /**
         * Reading-order line indices for word boxes that carry no structural
         * line information (stored OCR boxes). A word starts a new line when its
         * vertical overlap with the running line band is less than half the
         * smaller of the two heights — scale-free, so it works on normalized
         * and page-pt coordinates alike.
         */
        // @spec VIEW-SEL-008
        fun assignLineIndices(boxes: List<Box>): IntArray {
            val lines = IntArray(boxes.size)
            if (boxes.isEmpty()) return lines
            var line = 0
            var bandTop = boxes[0].top
            var bandBottom = boxes[0].bottom
            for (i in 1 until boxes.size) {
                val b = boxes[i]
                val overlap = min(bandBottom, b.bottom) - max(bandTop, b.top)
                val minH = min(abs(bandBottom - bandTop), abs(b.bottom - b.top))
                if (overlap < minH * 0.5f) {
                    line++
                    bandTop = b.top
                    bandBottom = b.bottom
                } else {
                    bandTop = min(bandTop, b.top)
                    bandBottom = max(bandBottom, b.bottom)
                }
                lines[i] = line
            }
            return lines
        }
    }
}
