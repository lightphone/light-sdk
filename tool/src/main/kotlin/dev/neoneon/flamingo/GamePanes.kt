package dev.neoneon.flamingo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import dev.neoneon.chesskit.Piece
import dev.neoneon.chesskit.Square

// The pieces of the panes a game screen lays over its board — the promotion picker, an actions
// list, a message. Shared by GameView and HotSeatScreen, so both read the same.

// The four pieces a pawn on the last rank can become. Lifted out of the old PromotionPane so the
// notifications pane can host it rather than duplicate it.
@Composable
internal fun ColumnScope.PromotionOptions(
    color: Piece.Color,
    square: Square,
    onSelect: (Piece.Kind) -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalArrangement = Arrangement.Center,
    ) {
        for (kind in listOf(Piece.Kind.queen, Piece.Kind.rook, Piece.Kind.bishop, Piece.Kind.knight)) {
            PromotionOption(piece = Piece(kind, color, square), onSelect = { onSelect(kind) })
        }
    }
}

// One row of the actions list. Shaped like PromotionOption without a piece glyph. A row that
// isn't available right now is greyed rather than dropped: one that vanished on the opponent's
// turn would read as a bug.
@Composable
internal fun ActionRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    LightText(
        text = label,
        variant = LightTextVariant.Copy,
        lighten = !enabled,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.lightClickable(role = Role.Button) { onClick() } else Modifier)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    )
}

@Composable
internal fun ColumnScope.CenteredMessage(text: String) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = text,
            variant = LightTextVariant.Copy,
            align = TextAlign.Center,
        )
    }
}

// One row of the promotion picker. Shaped like CreateGameScreen's ColorOption, but tapping picks
// immediately rather than arming a confirm button, so there's no selected/unselected marker.
@Composable
private fun PromotionOption(piece: Piece, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(role = Role.Button) { onSelect() }
            .padding(vertical = 0.75f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            // Same colour inversion ChessBoard uses, so the glyphs match the pieces on the board.
            text = piece.copy(color = piece.color.opposite).graphic,
            variant = LightTextVariant.Heading,
        )
        LightText(
            text = piece.kind.toString(),
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(start = 1f.gridUnitsAsDp()),
        )
    }
}
