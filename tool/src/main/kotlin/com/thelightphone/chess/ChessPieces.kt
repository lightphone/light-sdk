package com.thelightphone.chess

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.thelightphone.chess.engine.BISHOP
import com.thelightphone.chess.engine.KING
import com.thelightphone.chess.engine.KNIGHT
import com.thelightphone.chess.engine.PAWN
import com.thelightphone.chess.engine.QUEEN
import com.thelightphone.chess.engine.ROOK
import com.thelightphone.chess.engine.WHITE
import com.thelightphone.chess.engine.colorOf
import com.thelightphone.chess.engine.typeOf

fun pieceDrawableRes(piece: Int): Int {
    val white = colorOf(piece) == WHITE
    return when (typeOf(piece)) {
        KING -> if (white) R.drawable.chess_white_king else R.drawable.chess_black_king
        QUEEN -> if (white) R.drawable.chess_white_queen else R.drawable.chess_black_queen
        ROOK -> if (white) R.drawable.chess_white_rook else R.drawable.chess_black_rook
        BISHOP -> if (white) R.drawable.chess_white_bishop else R.drawable.chess_black_bishop
        KNIGHT -> if (white) R.drawable.chess_white_knight else R.drawable.chess_black_knight
        PAWN -> if (white) R.drawable.chess_white_pawn else R.drawable.chess_black_pawn
        else -> 0
    }
}

@Composable
fun ChessPieceImage(
    piece: Int,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val res = pieceDrawableRes(piece)
    if (res == 0) return
    Image(
        painter = painterResource(res),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
