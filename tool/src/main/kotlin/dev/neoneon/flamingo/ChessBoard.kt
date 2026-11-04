package dev.neoneon.flamingo

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.lightClickable
import dev.neoneon.chesskit.Board
import dev.neoneon.chesskit.Piece
import dev.neoneon.chesskit.Position
import dev.neoneon.chesskit.Square
import kotlin.math.sqrt

// Same weight as the SDK's active code-input underline, so the check marker reads as a line
// rather than a hairline (see LightCodeInput's ACTIVE_UNDERLINE_THICKNESS_PX).
private const val CHECK_UNDERLINE_THICKNESS_PX = 7f
private const val CHECK_UNDERLINE_DURATION_MS = 220

// The board is an old newspaper diagram whatever the LightOS theme: black ink on almost-white
// newsprint, light squares bare and dark squares hatched. The paper is a neutral grey rather than
// the warm #F4EFE2 of iOS's newspaper-pieces preview, because LightOS is monochrome.
internal val NEWSPRINT_PAPER = Color(0xFFF2F2F2)
internal val NEWSPRINT_INK = Color.Black

// Dark-square hatching: "/" strokes, bottom-left to top-right as iOS's SquareView draws them.
// A whole number of strokes per square edge puts one through every corner, so the hatching runs on
// unbroken from one dark square into the next along a diagonal. Ink covers a quarter of the
// square, as in iOS's preview (1px of ink in every 4px, measured across the strokes).
private const val HATCH_STROKES_PER_SQUARE = 10
private const val HATCH_INK_COVERAGE = 0.25f

// The paper halo around a piece on a hatched square — the gap the old diagram blocks left so the
// hatching stops short of the piece's outline instead of running into it. A stroke of the piece's
// silhouette, as a share of the glyph's font size; half of it shows outside the silhouette.
private const val PIECE_HALO_STROKE_SHARE = 0.12f

// A piece glyph's font size, as a share of its square's side, so the pieces grow with the board
// rather than staying at a text size that left them small in their squares.
private const val PIECE_GLYPH_SQUARE_SHARE = 0.70f

// How far a piece glyph is moved down from the middle of its square, as a share of the square's
// side. Set by eye on the board.
private const val PIECE_GLYPH_DROP_SHARE = 0.02f

// The last move is framed in ink: a dashed frame on the square it left, a solid one on the square
// it reached. iOS shades both squares instead, but a light shade on an empty square read as just
// another grey on the LPIII, next to the grey target marks. The frames are half the selection
// frame's weight and set further in, so they never read as a selection, and a selection drawn over
// one leaves it showing. Sizes are shares of a square's side. On a hatched square the frame is
// cased in paper, so it isn't lost among the strokes.
private const val LAST_MOVE_FRAME_STROKE_SHARE = 0.035f
private const val LAST_MOVE_FRAME_INSET_SHARE = 0.10f
private const val LAST_MOVE_FRAME_CASING_SHARE = 0.025f
// Dashes on each side of the from-square's frame. Each side starts and ends on a dash, so the
// corners stay closed, with gaps as long as the dashes in between.
private const val LAST_MOVE_FROM_DASHES_PER_SIDE = 3

// A selection and its legal targets are marked on top of a square: an ink frame inside the selected
// square, a dot on an empty target, solid corners on a capture. The targets are light grey, so they
// sit back behind the pieces and the selection. Sizes are shares of a square's side. A dot on a
// hatched square is cased in paper, so the hatching stops short of it as it does of a piece.
private val TARGET_MARK_GREY = Color(0xFFB4B4B4)
private const val SELECTION_FRAME_STROKE_SHARE = 0.07f
private const val TARGET_DOT_DIAMETER_SHARE = 0.30f
private const val TARGET_DOT_CASING_SHARE = 0.05f
private const val CAPTURE_CORNER_LEG_SHARE = 0.26f

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
 * It's drawn as a newspaper diagram in either LightOS theme — ink on newsprint, dark squares
 * hatched — so nothing on it uses the theme's colors: white pieces are paper with an ink outline,
 * black pieces solid ink.
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
 * [selectedSquare] is framed in ink, and each of [legalTargets] carries a light grey dot — or corners,
 * when it holds a piece to capture. The selection frame is the heavier, so it never reads as one of
 * [lastMove]'s.
 *
 * [lastMove] frames the move's origin square dashed and its destination square solid (see
 * [LastMoveEnd]). Always shown for the move a finished game ended on (or, mid-replay, whichever
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
                            lastMoveEnd = lastMoveEnd(square, lastMove),
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

/** Which end of the last move a square is: the one it left or the one it reached. */
internal enum class LastMoveEnd { From, To }

/** [square]'s end of [lastMove], given as (from, to), or `null` if the move didn't touch it. */
internal fun lastMoveEnd(square: Square, lastMove: Pair<Square, Square>?): LastMoveEnd? = when (square) {
    lastMove?.first -> LastMoveEnd.From
    lastMove?.second -> LastMoveEnd.To
    else -> null
}

/**
 * Hatches a dark square in ink, over whatever its background already drew. The strokes start a
 * full square to the left so they reach the bottom-right corner too; the clip trims the overhang.
 */
private fun Modifier.newspaperHatch(): Modifier = drawWithCache {
    val spacing = size.width / HATCH_STROKES_PER_SQUARE
    val strokes = Path().apply {
        var x = -size.height
        while (x < size.width) {
            moveTo(x, size.height)
            lineTo(x + size.height, 0f)
            x += spacing
        }
    }
    // At 45° the strokes repeat every spacing/√2 measured across them.
    val stroke = Stroke(width = spacing / sqrt(2f) * HATCH_INK_COVERAGE)
    onDrawBehind {
        clipRect { drawPath(strokes, NEWSPRINT_INK, style = stroke) }
    }
}

/**
 * Frames a square that [end] of the last move touched, over its hatching and under any selection
 * mark and its piece. [isDark] cases the frame in paper.
 */
private fun Modifier.lastMoveFrame(end: LastMoveEnd?, isDark: Boolean): Modifier = when (end) {
    null -> this
    else -> drawBehind { drawLastMoveFrame(dashed = end == LastMoveEnd.From, cased = isDark) }
}

/**
 * Marks a selected square or a legal target, over its hatching and under its piece.
 */
private fun Modifier.selectionMarks(
    isSelected: Boolean,
    isLegalTarget: Boolean,
    isCapture: Boolean,
): Modifier = when {
    isSelected -> drawBehind { drawSelectionFrame() }
    isLegalTarget && isCapture -> drawBehind { drawCaptureCorners() }
    isLegalTarget -> drawBehind { drawTargetDot() }
    else -> this
}

/**
 * A thin ink frame set in from the square's edges, [dashed] for the square the move left and solid
 * for the one it reached. [cased] lays it on a wider band of paper first, the same dashes for a
 * dashed frame, so the hatching shows between them.
 */
private fun DrawScope.drawLastMoveFrame(dashed: Boolean, cased: Boolean) {
    val width = size.minDimension * LAST_MOVE_FRAME_STROKE_SHARE
    val inset = size.minDimension * LAST_MOVE_FRAME_INSET_SHARE
    val side = size.minDimension - 2 * inset
    val effect = if (dashed) {
        val dash = side / (2 * LAST_MOVE_FROM_DASHES_PER_SIDE - 1)
        PathEffect.dashPathEffect(floatArrayOf(dash, dash))
    } else {
        null
    }
    // Each side drawn on its own, so every one starts on a dash; square caps close the corners.
    val corners = listOf(
        Offset(inset, inset),
        Offset(size.width - inset, inset),
        Offset(size.width - inset, size.height - inset),
        Offset(inset, size.height - inset),
    )
    fun drawSides(color: Color, strokeWidth: Float) {
        for (i in corners.indices) {
            drawLine(
                color,
                start = corners[i],
                end = corners[(i + 1) % corners.size],
                strokeWidth = strokeWidth,
                cap = StrokeCap.Square,
                pathEffect = effect,
            )
        }
    }
    if (cased) drawSides(NEWSPRINT_PAPER, width + 2 * size.minDimension * LAST_MOVE_FRAME_CASING_SHARE)
    drawSides(NEWSPRINT_INK, width)
}

/** A heavy ink frame just inside the square's edges. */
private fun DrawScope.drawSelectionFrame() {
    val width = size.minDimension * SELECTION_FRAME_STROKE_SHARE
    drawRect(
        NEWSPRINT_INK,
        topLeft = Offset(width / 2, width / 2),
        size = Size(size.width - width, size.height - width),
        style = Stroke(width),
    )
}

/** A solid light grey dot at the square's middle, ringed in paper so a hatched square's strokes stop short. */
private fun DrawScope.drawTargetDot() {
    val radius = size.minDimension * TARGET_DOT_DIAMETER_SHARE / 2
    drawCircle(NEWSPRINT_PAPER, radius + size.minDimension * TARGET_DOT_CASING_SHARE)
    drawCircle(TARGET_MARK_GREY, radius)
}

/** Solid light grey triangles in the square's four corners, which a piece never reaches. */
private fun DrawScope.drawCaptureCorners() {
    val leg = size.minDimension * CAPTURE_CORNER_LEG_SHARE
    val corners = Path().apply {
        for ((x, dx) in listOf(0f to 1f, size.width to -1f)) {
            for ((y, dy) in listOf(0f to 1f, size.height to -1f)) {
                moveTo(x, y)
                lineTo(x + dx * leg, y)
                lineTo(x, y + dy * leg)
                close()
            }
        }
    }
    drawPath(corners, TARGET_MARK_GREY)
}

@Composable
private fun ChessSquare(
    piece: Piece?,
    isDark: Boolean,
    isSelected: Boolean,
    isLegalTarget: Boolean,
    isInCheck: Boolean,
    lastMoveEnd: LastMoveEnd?,
    lastMoveDescription: String?,
    squareSize: Dp,
    onTap: () -> Unit,
) {
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
            .background(NEWSPRINT_PAPER)
            .then(if (isDark) Modifier.newspaperHatch() else Modifier)
            .lastMoveFrame(lastMoveEnd, isDark)
            .selectionMarks(isSelected, isLegalTarget, isCapture = piece != null)
            .lightClickable(onClick = onTap)
            .then(markerSemantics),
        contentAlignment = Alignment.Center,
    ) {
        if (piece != null) {
            NewspaperPieceGlyph(piece = piece, haloColor = NEWSPRINT_PAPER.takeIf { isDark }, squareSize = squareSize)
        }
        if (checkProgress > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(checkProgress)
                    .height(CHECK_UNDERLINE_THICKNESS_PX.designVerticalPxToDp())
                    .background(NEWSPRINT_INK),
            )
        }
    }
}

/**
 * [piece]'s glyph as a newspaper diagram prints it: a white piece is the outline glyph in ink over
 * the solid one in paper, so the square's hatching can't show through it; a black piece is the
 * solid glyph in ink.
 *
 * [haloColor], given on a hatched square, rings the piece in paper, so the hatching stops short of
 * its outline — the paper halo iOS's newspaper-pieces preview gives a piece on a dark square.
 *
 * The glyph is sized from [squareSize] (see [pieceGlyphStyle]) and moved down by
 * [PIECE_GLYPH_DROP_SHARE] of it.
 */
@Composable
private fun NewspaperPieceGlyph(piece: Piece, haloColor: Color?, squareSize: Dp) {
    val style = pieceGlyphStyle(squareSize)
    val solid = piece.copy(color = Piece.Color.black).graphic
    Box(
        modifier = Modifier.offset(y = squareSize * PIECE_GLYPH_DROP_SHARE),
        contentAlignment = Alignment.Center,
    ) {
        if (haloColor != null) {
            val haloWidth = with(LocalDensity.current) { style.fontSize.toPx() } * PIECE_HALO_STROKE_SHARE
            BasicText(
                text = solid,
                modifier = Modifier.clearAndSetSemantics {},
                style = style.copy(
                    color = haloColor,
                    drawStyle = Stroke(width = haloWidth, join = StrokeJoin.Round),
                ),
            )
        }
        if (piece.color == Piece.Color.white) {
            BasicText(
                text = solid,
                modifier = Modifier.clearAndSetSemantics {},
                style = style.copy(color = NEWSPRINT_PAPER),
            )
        }
        BasicText(text = piece.graphic, style = style.copy(color = NEWSPRINT_INK))
    }
}

/**
 * LightText's Heading face for [BasicText], which the piece's layers need so they can carry a
 * stroke and land on exactly the same spot, at a font size that's [PIECE_GLYPH_SQUARE_SHARE] of
 * [squareSize]. The line height is the font size and there's no font padding, so the text box
 * never outgrows the square.
 */
@Composable
private fun pieceGlyphStyle(squareSize: Dp): TextStyle {
    val fontSize = with(LocalDensity.current) { (squareSize * PIECE_GLYPH_SQUARE_SHARE).toSp() }
    return LightThemeTokens.typography.heading.copy(
        fontSize = fontSize,
        lineHeight = fontSize,
        textAlign = TextAlign.Center,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
}
