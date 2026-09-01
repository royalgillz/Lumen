package com.lumen.app.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSelectionModelTest {

    // Two lines in reading order:
    //   line 0: "The"(0..30) "quick"(35..65) "brown"(70..100)   y 0..10
    //   line 1: "fox"(0..30) "jumps"(35..65)                    y 20..30
    private fun words() = listOf(
        TextSelectionModel.Word("The", 0f, 0f, 30f, 10f, 0),
        TextSelectionModel.Word("quick", 35f, 0f, 65f, 10f, 0),
        TextSelectionModel.Word("brown", 70f, 0f, 100f, 10f, 0),
        TextSelectionModel.Word("fox", 0f, 20f, 30f, 30f, 1),
        TextSelectionModel.Word("jumps", 35f, 20f, 65f, 30f, 1),
    )

    private fun model() = TextSelectionModel(words())

    // @spec VIEW-SEL-001
    @Test
    fun longPress_selectsSingleWordUnderPoint() {
        val m = model()
        assertTrue(m.selectWordAt(40f, 5f, slop = 2f))
        assertEquals(1, m.startIndex)
        assertEquals(1, m.endIndex)
        assertEquals("quick", m.selectedText())
    }

    // @spec VIEW-SEL-001
    @Test
    fun longPress_offAnyWord_selectsNothing() {
        val m = model()
        assertFalse(m.selectWordAt(200f, 200f, slop = 2f))
        assertFalse(m.hasSelection)
        assertEquals("", m.selectedText())
    }

    // @spec VIEW-SEL-002
    @Test
    fun dragEndHandle_extendsByWholeWordsInReadingOrder() {
        val m = model()
        m.selectWordAt(40f, 5f, 2f) // "quick"
        val stillStart = m.dragHandle(draggingStart = false, index = 3)
        assertFalse(stillStart)
        assertEquals(1, m.startIndex)
        assertEquals(3, m.endIndex)
    }

    // @spec VIEW-SEL-002
    @Test
    fun dragEndHandle_backwards_shrinksSelection() {
        val m = model()
        m.selectWordAt(40f, 5f, 2f)
        m.dragHandle(draggingStart = false, index = 4)
        m.dragHandle(draggingStart = false, index = 2)
        assertEquals(1, m.startIndex)
        assertEquals(2, m.endIndex)
    }

    // Dragging the start handle past the end swaps the handles' roles: the
    // finger keeps dragging, but it now holds the end handle.
    // @spec VIEW-SEL-002
    @Test
    fun dragStartHandle_pastEnd_swapsAnchor() {
        val m = model()
        m.selectWordAt(40f, 5f, 2f) // 1..1
        val stillStart = m.dragHandle(draggingStart = true, index = 3)
        assertFalse(stillStart)
        assertEquals(1, m.startIndex)
        assertEquals(3, m.endIndex)
    }

    // @spec VIEW-SEL-002
    @Test
    fun dragEndHandle_beforeStart_swapsAnchor() {
        val m = model()
        m.selectWordAt(75f, 5f, 2f) // "brown", index 2
        val nowStart = m.dragHandle(draggingStart = false, index = 0)
        assertTrue(nowStart)
        assertEquals(0, m.startIndex)
        assertEquals(2, m.endIndex)
    }

    // @spec VIEW-SEL-002
    @Test
    fun dragHandle_indexOutOfRange_clampsToWordList() {
        val m = model()
        m.selectWordAt(40f, 5f, 2f)
        m.dragHandle(draggingStart = false, index = 99)
        assertEquals(4, m.endIndex)
        m.dragHandle(draggingStart = true, index = -7)
        assertEquals(0, m.startIndex)
    }

    // @spec VIEW-SEL-002
    @Test
    fun indexNear_pointInsideWord_returnsThatWord() {
        assertEquals(1, model().indexNear(40f, 5f))
        assertEquals(4, model().indexNear(50f, 25f))
    }

    // Reading-order clamping: past the line's end lands on its last word, before
    // its start on the first; above/below the page clamps to the first/last word.
    // @spec VIEW-SEL-002, VIEW-SEL-007
    @Test
    fun indexNear_offWordPoints_clampInReadingOrder() {
        val m = model()
        assertEquals(2, m.indexNear(150f, 5f)) // right of line 0
        assertEquals(0, m.indexNear(-10f, 5f)) // left of line 0
        assertEquals(0, m.indexNear(15f, -50f)) // above everything
        assertEquals(4, m.indexNear(100f, 500f)) // below everything
        assertEquals(1, m.indexNear(36f, 12f)) // in the line gap, nearer line 0
    }

    // @spec VIEW-SEL-003
    @Test
    fun selectionBoxes_unionPerSourceLine() {
        val m = model()
        m.selectWordAt(40f, 5f, 2f)
        m.dragHandle(draggingStart = false, index = 4)
        val boxes = m.selectionBoxes()
        assertEquals(2, boxes.size)
        assertEquals(TextSelectionModel.Box(35f, 0f, 100f, 10f), boxes[0])
        assertEquals(TextSelectionModel.Box(0f, 20f, 65f, 30f), boxes[1])
    }

    // @spec VIEW-SEL-005
    @Test
    fun selectedText_spacesWithinLine_lineBreaksBetweenLines() {
        val m = model()
        m.selectWordAt(40f, 5f, 2f)
        m.dragHandle(draggingStart = false, index = 4)
        assertEquals("quick brown\nfox jumps", m.selectedText())
    }

    // @spec VIEW-SEL-005
    @Test
    fun selectedText_wholePage_readsInOrder() {
        val m = model()
        m.selectWordAt(5f, 5f, 2f)
        m.dragHandle(draggingStart = false, index = 4)
        assertEquals("The quick brown\nfox jumps", m.selectedText())
    }

    @Test
    fun emptyWordList_isInertEverywhere() {
        val m = TextSelectionModel(emptyList())
        assertFalse(m.selectWordAt(0f, 0f, 2f))
        assertEquals(-1, m.indexNear(0f, 0f))
        assertTrue(m.selectionBoxes().isEmpty())
        assertEquals("", m.selectedText())
    }

    // OCR boxes carry no structural line order — vertical overlap derives it.
    // @spec VIEW-SEL-008
    @Test
    fun assignLineIndices_groupsByVerticalOverlap() {
        val boxes = listOf(
            TextSelectionModel.Box(0f, 0f, 10f, 10f),
            TextSelectionModel.Box(12f, 2f, 22f, 12f), // slight baseline jitter: same line
            TextSelectionModel.Box(0f, 20f, 10f, 30f), // clearly below: new line
            TextSelectionModel.Box(12f, 21f, 22f, 31f), // same second line
        )
        val lines = TextSelectionModel.assignLineIndices(boxes)
        assertEquals(listOf(0, 0, 1, 1), lines.toList())
    }

    // @spec VIEW-SEL-008
    @Test
    fun assignLineIndices_smallOverlap_startsNewLine() {
        val boxes = listOf(
            TextSelectionModel.Box(0f, 0f, 10f, 10f),
            TextSelectionModel.Box(12f, 8f, 22f, 18f), // overlap 2 of height 10: new line
        )
        assertEquals(listOf(0, 1), TextSelectionModel.assignLineIndices(boxes).toList())
    }

    // Scale-free: normalized (0..1) coordinates behave the same as page points.
    // @spec VIEW-SEL-008
    @Test
    fun assignLineIndices_worksOnNormalizedCoordinates() {
        val boxes = listOf(
            TextSelectionModel.Box(0.0f, 0.10f, 0.2f, 0.12f),
            TextSelectionModel.Box(0.3f, 0.10f, 0.5f, 0.12f),
            TextSelectionModel.Box(0.0f, 0.15f, 0.2f, 0.17f),
        )
        assertEquals(listOf(0, 0, 1), TextSelectionModel.assignLineIndices(boxes).toList())
    }

    @Test
    fun assignLineIndices_emptyInput() {
        assertEquals(0, TextSelectionModel.assignLineIndices(emptyList()).size)
    }
}
