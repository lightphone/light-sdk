package com.thelightphone.sdk.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LightScrollViewLayoutTest {

    @Test
    fun contentWidthIsInvariantToScrollBarVisibility() {
        val total = 100f
        val position = LightScrollBarPosition.Outside
        val widthWhenBarHidden = scrollViewContentWidthUnits(total, position)
        val widthWhenBarShown = scrollViewContentWidthUnits(total, position)
        assertEquals(widthWhenBarHidden, widthWhenBarShown)
    }

    @Test
    fun outsideReservesAGutter() {
        assertTrue(scrollBarGutterUnits(LightScrollBarPosition.Outside) > 0f)
    }

    @Test
    fun insideReservesNoGutter() {
        assertEquals(0f, scrollBarGutterUnits(LightScrollBarPosition.Inside))
    }

    @Test
    fun outsideContentWidthIsTotalMinusGutter() {
        val total = 100f
        val expected = total - scrollBarGutterUnits(LightScrollBarPosition.Outside)
        assertEquals(expected, scrollViewContentWidthUnits(total, LightScrollBarPosition.Outside))
    }

    @Test
    fun aspectRatioContentDoesNotOscillate() {
        val total = 100f
        val gutter = scrollBarGutterUnits(LightScrollBarPosition.Outside)
        val overflowThreshold = total - gutter / 2f
        fun overflows(width: Float) = width > overflowThreshold
        fun contentWidth() = scrollViewContentWidthUnits(total, LightScrollBarPosition.Outside)

        var shown = false
        val states = mutableListOf(shown)
        repeat(10) {
            shown = overflows(contentWidth())
            states.add(shown)
        }
        assertEquals(
            1,
            states.takeLast(4).toSet().size,
            "scrollbar visibility must reach a stable fixpoint, not oscillate: $states",
        )
    }
}
