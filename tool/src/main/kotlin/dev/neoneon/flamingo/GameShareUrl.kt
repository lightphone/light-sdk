package dev.neoneon.flamingo

import android.net.Uri

private const val INVITE_BASE_URL = "https://neoneon.dev/flamingo"

/**
 * Builds the invite URL for [gameId]'s last move, matching the shape iOS's
 * `GameURL.encode` produces for a real chess move: [preMoveFen] is the position the
 * move was played *from* and [lan] is the move itself — iOS applies `lan` to `fen` to
 * reach the current position, exactly like every other message in this protocol.
 * Sharing the post-move fen with no `lan` would leave iOS with nothing to apply.
 */
fun buildInviteUrl(gameId: String, preMoveFen: String, lan: String, playerId: String): Uri =
    Uri.parse("$INVITE_BASE_URL/games/$gameId").buildUpon()
        .appendQueryParameter("fen", preMoveFen)
        .appendQueryParameter("lan", lan)
        .appendQueryParameter("pid", playerId)
        .appendQueryParameter("mid", playerId)
        .build()
