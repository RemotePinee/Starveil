package com.starveil.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DampedSheetStateTest {
    @Test
    fun `half sheet keeps footer inside visible viewport`() {
        // 2400px window, 96px status bar, 48px navigation bar, 64px handle.
        val sheetHeight = 2400 - 96 - 48
        val bodyHeight = sheetHeight - 64
        val offset = sheetHeight / 2f
        val visible = sheetVisibleHeight(bodyHeight, offset)
        assertEquals(1064, visible)
        assertEquals(2352f, 96 + offset + 64 + visible, 0f)
        assertEquals(bodyHeight, sheetVisibleHeight(bodyHeight, 0f))
        assertEquals(0, sheetVisibleHeight(bodyHeight, sheetHeight.toFloat()))
    }

    @Test
    fun `drag resistance increases near boundary without crossing status bar`() {
        val normalDelta = 500f - sheetDragOffset(500f, -100f, 1000f)
        val edgeDelta = 50f - sheetDragOffset(50f, -100f, 1000f)
        assertTrue(normalDelta > edgeDelta)
        assertTrue(normalDelta < 100f)
        assertEquals(0f, sheetDragOffset(20f, -1000f, 1000f), 0f)
        assertEquals(1000f, sheetDragOffset(980f, 1000f, 1000f), 0f)
        assertEquals(SheetAnchor.Half, sheetTarget(0f, 1000f, 1000f))
        assertEquals(SheetAnchor.Hidden, sheetTarget(500f, 1000f, 1000f))
        assertEquals(SheetAnchor.Half, sheetTarget(400f, 0f, 1000f))
    }

    @Test
    fun `bounds changes preserve expanded sheet rather than reset to half`() {
        val state = DampedSheetState()
        state.updateBounds(2200, 2400)
        state.dragBy(-10000f)
        assertEquals(0f, state.offsetPx, 0f)
        // Expanded is also the nearest target after a zero-velocity release.
        assertEquals(SheetAnchor.Expanded, sheetTarget(state.offsetPx, 0f, 2200f))
    }
}
