package dev.neoneon.flamingo

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToDp
import dev.neoneon.chesskit.Board
import dev.neoneon.chesskit.Piece
import dev.neoneon.chesskit.Position
import dev.neoneon.chesskit.Square

// Same weight as the SDK's active code-input underline, so the check marker reads as a line
// rather than a hairline (see LightCodeInput's ACTIVE_UNDERLINE_THICKNESS_PX).
private const val CHECK_UNDERLINE_THICKNESS_PX = 7f
private const val CHECK_UNDERLINE_DURATION_MS = 220

// The last-move shade, as the square's background alpha. Each sits clearly above its plain
// counterpart (dark 0.12, light 0.03) and below a selection (0.35), the way iOS lays a translucent
// fill over the from- and to-squares — a whole square is legible at a glance on the LPIII's
// screen, where an earlier dot-and-arrow marker was too small to notice.
private const val LAST_MOVE_DARK_ALPHA = 0.28f
private const val LAST_MOVE_LIGHT_ALPHA = 0.18f

/**
 * The square holding the king that [state] reports as being in check, or `null` if neither is.
 *
 * Checkmate counts too: the king is in check there as well, and it's the moment most worth
 * marking.
 *
 * This reads [Board.state] rather than deriving check from [position] alone because chesskit keeps
 * its attack generation private. Note [Board] only evaluates the right king when its state was
 * updated *by a move* (see `Board.updateState`), so a board advanced with `move` /
 * `completePromotion` — including one rebuilt by replaying a game's moves — reports check
 * correctly, while one whose position was set via `Board.update` reads as `active` even from a
 * position that really is check.
 */
internal fun checkedKingSquare(state: Board.State, position: Position): Square? {
    val color = when (state) {
        is Board.State.check -> state.color
        is Board.State.checkmate -> state.color
        else -> null
    } ?: return null

    return position.pieces.firstOrNull { it.kind == Piece.Kind.king && it.color == color }?.square
}

/**
 * Interactive chess board rendering a chesskit [Position] with tap-to-select/tap-to-move squares.
 *
 * [orientation] is the local player's color: their pieces sit at the bottom, so a black
 * player sees the board flipped (rank 1 on top, files h→a left-to-right).
 *
 * [boardSize] is the full side length of the (square) board; each square is [boardSize] / 8. The
 * caller sizes it to fit the space actually available (see GameView), so it never overflows the
 * screen regardless of device proportions.
 *
 * [checkedKingSquare] marks the king that's in check with a line along the bottom edge of that one
 * square, drawing in from its left edge to its right. Callers derive it from their board with the
 * `checkedKingSquare` function above.
 *
 * [lastMove] shades the move's origin and destination squares a step darker than their plain
 * color, as iOS does. Always shown for the move a finished game ended on (or, mid-replay, whichever
 * move produced the frame on screen); during live play it's the caller's choice whether to pass one.
 */
@Composable
fun ChessBoard(
    position: Position,
    selectedSquare: Square?,
    legalTargets: Set<Square>,
    onSquareTap: (Square) -> Unit,
    boardSize: Dp,
    modifier: Modifier = Modifier,
    orientation: Piece.Color = Piece.Color.white,
    checkedKingSquare: Square? = null,
    lastMove: Pair<Square, Square>? = null,
) {
    val squareSize = boardSize / 8
    val rankOrder = boardRankOrder(orientation)
    val fileOrder = boardFileOrder(orientation)
    Box(modifier = modifier.size(boardSize)) {
        Column {
            for (rankValue in rankOrder) {
                Row {
                    for (file in fileOrder) {
                        val square = Square(file, Square.Rank(rankValue))
                        ChessSquare(
                            piece = position.piece(at = square),
                            isDark = square.color == Square.Color.dark,
                            isSelected = square == selectedSquare,
                            isLegalTarget = square in legalTargets,
                            isInCheck = square == checkedKingSquare,
                            isLastMoveSquare = lastMove?.let { square == it.first || square == it.second } == true,
                            lastMoveDescription = lastMove
                                ?.takeIf { it.second == square }
                                ?.let { "Last move ${it.first.notation} to ${it.second.notation}" },
                            squareSize = squareSize,
                            onTap = { onSquareTap(square) },
                        )
                    }
                }
            }
        }
    }
}

/** The left-to-right file order the board lays out in, seen by a player of [orientation]. */
internal fun boardFileOrder(orientation: Piece.Color): List<Square.File> =
    if (orientation == Piece.Color.black) Square.File.entries.reversed() else Square.File.entries

/** The top-to-bottom rank order the board lays out in: your own back rank is nearest you. */
internal fun boardRankOrder(orientation: Piece.Color): List<Int> =
    (if (orientation == Piece.Color.black) (1..8) else (8 downTo 1)).toList()

/**
 * The background alpha of a square: a selection first, then a legal target, then the last move's
 * shade, then the square's plain color. Pulled out of [ChessSquare] so the precedence is testable.
 */
internal fun squareBackgroundAlpha(
    isDark: Boolean,
    isSelected: Boolean,
    isLegalTarget: Boolean,
    isLastMoveSquare: Boolean,
): Float = when {
    isSelected -> 0.35f
    isLegalTarget -> 0.25f
    isLastMoveSquare -> if (isDark) LAST_MOVE_DARK_ALPHA else LAST_MOVE_LIGHT_ALPHA
    isDark -> 0.12f
    else -> 0.03f
}

@Composable
private fun ChessSquare(
    piece: Piece?,
    isDark: Boolean,
    isSelected: Boolean,
    isLegalTarget: Boolean,
    isInCheck: Boolean,
    isLastMoveSquare: Boolean,
    lastMoveDescription: String?,
    squareSize: Dp,
    onTap: () -> Unit,
) {
    val content = LightThemeTokens.colors.content
    val backgroundAlpha = squareBackgroundAlpha(isDark, isSelected, isLegalTarget, isLastMoveSquare)

    // The check line draws in from this square's left edge to its right edge — screen-left to
    // screen-right, so it looks the same however the board is oriented. At 0f there's nothing to
    // draw, which is every square but the checked king's.
    val checkProgress by animateFloatAsState(
        targetValue = if (isInCheck) 1f else 0f,
        animationSpec = tween(durationMillis = CHECK_UNDERLINE_DURATION_MS, easing = LinearEasing),
        label = "checkUnderline",
    )
    // A square is never both the checked king's and a last move's endpoint at once — a king can't
    // move into check on itself — so there's never a second description competing for this slot.
    val markerDescription = if (isInCheck) "King in check" else lastMoveDescription
    val markerSemantics = markerDescription?.let { description ->
        Modifier.semantics { contentDescription = description }
    } ?: Modifier

    Box(
        modifier = Modifier
            .size(squareSize)
            .background(content.copy(alpha = backgroundAlpha))
            .clickable(onClick = onTap)
            .then(markerSemantics),
        contentAlignment = Alignment.Center,
    ) {
        if (piece != null) {
            LightText(
                text = piece.copy(color = piece.color.opposite).graphic,
                variant = LightTextVariant.Heading,
                align = TextAlign.Center,
            )
        }
        if (checkProgress > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(checkProgress)
                    .height(CHECK_UNDERLINE_THICKNESS_PX.designVerticalPxToDp())
                    .background(content),
            )
        }
    }
}
