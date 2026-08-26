package com.thelightphone.chess

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.thelightphone.chess.engine.EMPTY
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

@Composable
fun ChessBoard(
    occupancy: IntArray,
    playerIsWhite: Boolean,
    selected: Int?,
    targets: Set<Int>,
    lastFrom: Int?,
    lastTo: Int?,
    enabled: Boolean,
    onSquare: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LightThemeTokens.colors
    val ranks = if (playerIsWhite) 7 downTo 0 else 0..7
    val files = if (playerIsWhite) 0..7 else 7 downTo 0
    val leftFile = files.first()
    val bottomRank = ranks.last()
    val lightSquare = lerp(colors.background, colors.content, 0.50f)
    val darkSquare = lerp(colors.background, colors.content, 0.28f)
    val mark = colors.content
    val coordinate = lerp(colors.background, colors.content, 0.62f)

    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val square = min(maxWidth / 8, maxHeight / 8)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            for (rank in ranks) {
                Row {
                    for (file in files) {
                        val sq = rank * 16 + file
                        val piece = occupancy[rank * 8 + file]
                        val dark = ((file + rank) and 1) == 0
                        val isSelected = selected == sq
                        val isLast = sq == lastFrom || sq == lastTo
                        val bg = if (dark) darkSquare else lightSquare
                        Box(
                            modifier = Modifier
                                .size(square)
                                .background(bg)
                                .then(
                                    if (isSelected || isLast) {
                                        Modifier.border(2.dp, mark)
                                    } else {
                                        Modifier
                                    },
                                )
                                .lightClickable(enabled = enabled) { onSquare(sq) },
                        ) {
                            if (piece != EMPTY) {
                                ChessPieceImage(
                                    piece = piece,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(0.1f.gridUnitsAsDp()),
                                )
                            }
                            if (sq in targets && piece == EMPTY) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(0.5f.gridUnitsAsDp())
                                        .background(mark, CircleShape),
                                )
                            } else if (sq in targets) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .border(3.dp, mark),
                                )
                            }
                            if (file == leftFile) {
                                LightText(
                                    text = ('1' + rank).toString(),
                                    variant = LightTextVariant.Superfine,
                                    color = coordinate,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(start = 2.dp, top = 1.dp),
                                )
                            }
                            if (rank == bottomRank) {
                                LightText(
                                    text = ('a' + file).toString(),
                                    variant = LightTextVariant.Superfine,
                                    color = coordinate,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 2.dp, bottom = 1.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
