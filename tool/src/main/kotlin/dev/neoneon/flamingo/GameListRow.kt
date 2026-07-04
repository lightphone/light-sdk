package dev.neoneon.flamingo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant

@Composable
fun GameListRow(
    game: Game,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LightText(
            text = "Game ${game.id.take(8)}",
            variant = LightTextVariant.Copy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            text = game.status,
            variant = LightTextVariant.Detail,
            lighten = true,
        )
    }
}
