package dev.neoneon.flamingo

import dev.neoneon.chesskit.Position
import dev.neoneon.chesskit.Square

/** One position a game stood in, with everything the board should show alongside it. */
internal data class ReplayFrame(
    val position: Position,
    val lastMove: Pair<Square, Square>? = null,
    val checkedKingSquare: Square? = null,
)

/**
 * Every position a game has stood in, oldest first, and where the player has stepped to in it.
 *
 * Kept as an object rather than as a list and an index beside it on the view model, because the
 * view model publishes a `frameIndex` on its state class too. With both in scope inside the
 * `State.withCurrentBoard` extension the bare name resolved to the *state's* copy — the closer
 * receiver — so the cursor was read and written as a constant 0 and the finished-game board sat
 * on the opening position with the prev/next buttons dead. `replay.index` can only mean one thing.
 */
internal class ReplayLog {

    private val frames = mutableListOf<ReplayFrame>()

    /** Where the player has stepped to. Pinned to the newest frame while the game is still on. */
    var index: Int = 0
        private set

    /** How many positions there are to step through, the opening one included. */
    val size: Int get() = frames.size

    /** The frame the board should be showing, or null before anything has been recorded. */
    val current: ReplayFrame? get() = frames.getOrNull(index)

    /** Starts over, for a board that is being rebuilt from the server's record. */
    fun clear() {
        frames.clear()
        index = 0
    }

    /**
     * Appends [frame], unless its position is already the newest — which is the common case, since
     * most publishes change something other than the position (a selection, a notification) and
     * `StateFlow.update` may run its lambda more than once.
     */
    fun record(frame: ReplayFrame) {
        if (frames.lastOrNull()?.position?.fen == frame.position.fen) return
        frames += frame
        // Stay pinned to the newest frame while the game is still being played, so a game that
        // ends is already showing the position it ended on.
        index = frames.lastIndex
    }

    /**
     * Steps one position back or forward, stopping at either end.
     *
     * @return whether the cursor actually moved, so the caller can skip republishing at an end.
     */
    fun step(forward: Boolean): Boolean {
        val target = (index + if (forward) 1 else -1)
            .coerceIn(0, frames.lastIndex.coerceAtLeast(0))
        if (target == index) return false
        index = target
        return true
    }
}

/**
 * Where a replay stands, for the nav bar: `0` is the opening position, the rest are numbered by the
 * move that produced them. Shared by a finished online game and an archived local one.
 */
internal fun replayPositionLabel(frameIndex: Int, frameCount: Int): String =
    if (frameIndex == 0) "Start" else "Move $frameIndex of ${frameCount - 1}"
