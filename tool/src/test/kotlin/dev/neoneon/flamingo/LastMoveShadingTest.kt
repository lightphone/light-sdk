package dev.neoneon.flamingo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LastMoveShadingTest {

    private fun alpha(
        isDark: Boolean,
        isSelected: Boolean = false,
        isLegalTarget: Boolean = false,
        isLastMoveSquare: Boolean = false,
    ) = squareBackgroundAlpha(isDark, isSelected, isLegalTarget, isLastMoveSquare)

    @Test
    fun aLastMoveSquareIsShadedAboveItsPlainColor() {
        for (isDark in listOf(true, false)) {
            assertTrue(alpha(isDark, isLastMoveSquare = true) > alpha(isDark))
        }
    }

    @Test
    fun aLastMoveSquareStaysDistinctBetweenLightAndDark() {
        assertTrue(alpha(isDark = true, isLastMoveSquare = true) > alpha(isDark = false, isLastMoveSquare = true))
    }

    @Test
    fun selectionAndLegalTargetsOutrankTheLastMoveShade() {
        for (isDark in listOf(true, false)) {
            assertEquals(alpha(isDark, isSelected = true), alpha(isDark, isSelected = true, isLastMoveSquare = true))
            assertEquals(
                alpha(isDark, isLegalTarget = true),
                alpha(isDark, isLegalTarget = true, isLastMoveSquare = true),
            )
        }
    }
}
