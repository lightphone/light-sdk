package dev.neoneon.flamingo

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightColors
import com.thelightphone.sdk.ui.LightColorsPreviewProvider
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import dev.neoneon.chesskit.Board
import dev.neoneon.chesskit.Move
import dev.neoneon.chesskit.Piece
import dev.neoneon.chesskit.Position
import dev.neoneon.chesskit.Square
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "GameView"

/**
 * Whether the board should mark [lastMove] while a game is still live, per [visibility].
 *
 * A finished game is unaffected by this — it always shows its last move (see the board render call
 * site in [GameScreen]) — this only gates the live-play case, where showing it at all is optional.
 * [LastMoveVisibility.OpponentOnly] reads the mover's color off the piece now sitting on the
 * destination square rather than tracking it separately, which stays correct through a promotion
 * too (the promoted piece keeps the mover's color).
 */
internal fun shouldShowLiveLastMove(
    visibility: LastMoveVisibility,
    lastMove: Pair<Square, Square>?,
    position: Position,
    localColor: Piece.Color,
): Boolean {
    if (lastMove == null) return false
    return when (visibility) {
        LastMoveVisibility.Hidden -> false
        LastMoveVisibility.Latest -> true
        LastMoveVisibility.OpponentOnly -> position.piece(at = lastMove.second)?.color != localColor
    }
}

class GameViewViewModel(
    val gameId: String,
    private val identityStore: PlayerIdentityStore,
    private val lastMoveVisibilityStore: LastMoveVisibilityStore,
    // The color this device plays. A seed only: it's what "New game" (white) vs.
    // "Accept invite" (black) opened the screen as, and is overridden by the
    // server's record once the game is fetched (see loadExistingGame).
    initialColor: Piece.Color = Piece.Color.white,
) : LightViewModel<Unit>() {
    private var board = Board()
    private val api = FlamingoApi()
    private val transport: LiveTransport = KtorLiveTransport(viewModelScope)
    private var hasLoadedInitialState = false

    // The move the board is currently sitting on, and the two ways a game can end that leave no
    // trace on it — someone gave up, or both agreed. All three feed the finished-game presentation
    // through withCurrentBoard; none is derivable from the position.
    private var lastMove: Move? = null
    private var resignedColor: Piece.Color? = null
    private var agreedDraw = false

    /** The invite-phrase share overlay's state, layered over the board on demand. */
    sealed interface Share {
        data object Hidden : Share
        data object Loading : Share
        data class Shown(val phrase: String) : Share
        data class Error(val message: String) : Share
    }

    /**
     * A local move that reached the last rank and is waiting for the player to pick a piece.
     *
     * Not cancellable: by the time chesskit reports [Board.State.promotion] the pawn has already
     * been moved onto the last rank, and there's no takeback to undo it with. The move is also
     * held back from the socket until the choice is made, so the LAN we send carries the piece.
     *
     * The *pane* showing the picker is dismissible even so, because that held-back move is
     * exactly what makes abandoning one safe: the server has never heard of it, and still has
     * the pawn a rank short with our move outstanding. Any re-fetch restores that — which is why
     * [loadExistingGame] clears this rather than carrying a Pending across a rebuilt board.
     */
    sealed interface Promotion {
        data object Hidden : Promotion
        data class Pending(val move: Move, val preMoveFen: String) : Promotion
    }

    /**
     * Whether the notifications pane is on screen, and at which level.
     *
     * Deliberately orthogonal to *what* is pending — that lives in [Promotion] / [DrawOffer] /
     * [GameOutcome] — so the bell can show and hide the pane without disturbing the decisions
     * waiting inside it. Modelling the resign confirmation as a level of the pane rather than a
     * flag beside it makes "confirming while the pane is closed" unrepresentable, and gets it
     * dismissed by every path that closes the pane.
     */
    sealed interface Notifications {
        data object Hidden : Notifications
        data object Shown : Notifications
        data object ConfirmingResign : Notifications
    }

    data class State(
        val position: Position,
        val localColor: Piece.Color,
        val selectedSquare: Square? = null,
        val legalTargets: Set<Square> = emptySet(),
        val isLoading: Boolean = true,
        val playerId: String? = null,
        // Seat occupancy from the server record. A null seat means the game is still open for a
        // friend to join, so it can be shared; both stay null until the first load completes.
        val whitePlayerId: String? = null,
        val blackPlayerId: String? = null,
        val share: Share = Share.Hidden,
        val promotion: Promotion = Promotion.Hidden,
        // The square of the king currently in check (either color), marked on the board. Stored
        // rather than derived from [position], which alone can't answer it — see checkedKingSquare.
        val checkedKingSquare: Square? = null,
        // The from/to squares of the move just played, shaded on the board (always once the game is
        // over, per lastMoveVisibility while it's live), and how it ended. Null outcome means the
        // game is still on.
        val lastMove: Pair<Square, Square>? = null,
        val outcome: GameOutcome? = null,
        // The bell's toggle. Raised on its own when something lands that needs an answer; closed
        // by the bell, the pane's back button, or the decision being made.
        val notifications: Notifications = Notifications.Hidden,
        val drawOffer: DrawOffer = DrawOffer.None,
        // Where the player has stepped to in a finished game, and how many positions there are to
        // step through. Count is 0 while the game is on — there is nothing to replay yet.
        val frameIndex: Int = 0,
        val frameCount: Int = 0,
        // The player's chosen live-play marking, read reactively off LastMoveVisibilityStore (see
        // the init block) so a change made on the settings screen reaches a game already on the
        // back stack. Only gates the live board — see shouldShowLiveLastMove.
        val lastMoveVisibility: LastMoveVisibility = LastMoveVisibility.OpponentOnly,
    ) {
        /** A finished game with more than its opening position is worth stepping through. */
        val canReplay: Boolean get() = outcome != null && frameCount > 1

        /** There is an earlier position to step to. False on the opening position, where PREV would do nothing. */
        val canStepBack: Boolean get() = canReplay && frameIndex > 0

        /** There is a later position to step to. False on the final position, where NEXT would do nothing. */
        val canStepForward: Boolean get() = canReplay && frameIndex < frameCount - 1

        /** `0` is the opening position; the rest are numbered by the move that produced them. */
        val replayLabel: String get() = replayPositionLabel(frameIndex, frameCount)

        /** Only a game with an unfilled seat has anyone left to invite. */
        val hasOpenSeat: Boolean get() = whitePlayerId == null || blackPlayerId == null

        /** Something is waiting on the player: the pane has real content and the board is frozen. */
        val hasPendingNotification: Boolean
            get() = promotion is Promotion.Pending || drawOffer is DrawOffer.Incoming

        /** Both seats filled, so there's someone on the other end to make an offer to. */
        private val hasOpponent: Boolean get() = !isLoading && !hasOpenSeat

        /**
         * Offering needs a live opponent, a game still in play, nothing already outstanding, and
         * our own turn — the same gating the iOS companion applies to its Offer Draw menu item.
         */
        val canOfferDraw: Boolean
            get() = hasOpponent && outcome == null && drawOffer is DrawOffer.None &&
                promotion is Promotion.Hidden && position.sideToMove == localColor

        /** Resigning is gated on terminality alone: you may concede on the opponent's clock. */
        val canResign: Boolean
            get() = hasOpponent && outcome == null && promotion is Promotion.Hidden
    }

    private val _state = MutableStateFlow(State(position = board.position, localColor = initialColor))
    val state: StateFlow<State> = _state

    init {
        // Independent of onScreenShow's hasLoadedInitialState gate, so a setting flip on the
        // settings screen is picked up even while this screen sits backgrounded on the stack.
        viewModelScope.launch {
            lastMoveVisibilityStore.flow.collect { visibility ->
                _state.update { it.copy(lastMoveVisibility = visibility) }
            }
        }
    }

    // Every position the game has stood in, and where the player has stepped to in it. Recorded at
    // the one point every board advance publishes through, so the four sites that move a piece need
    // no bookkeeping of their own; re-entrant or repeated publishes are absorbed by [ReplayLog].
    private val replay = ReplayLog()

    // The frame the board is standing on right now: its position, the move that produced it, and
    // the check that move left behind. A plain member rather than something withCurrentBoard builds
    // inline, so `lastMove` here can only be the view model's — see [ReplayLog] for why that
    // distinction is load-bearing.
    private fun currentFrame(): ReplayFrame = ReplayFrame(
        position = board.position,
        lastMove = lastMove?.let { it.start to it.end },
        checkedKingSquare = checkedKingSquare(board.state, board.position),
    )

    // Every publish of a new board position goes through this, so the check marker, the last move
    // and the outcome can't be forgotten at one of the sites that advance the board (initial load,
    // incoming move, local move, promotion).
    private fun State.withCurrentBoard(): State {
        val live = currentFrame()
        replay.record(live)
        val outcome = gameOutcome(board.state, resignedColor, agreedDraw)
        // A finished game is read, not played: the board shows whichever frame the player has
        // stepped to, taken whole so the opening position can't be shown under the shading of the
        // move that ended the game. While the game is on there is only ever one frame worth
        // showing — the live one — so the playing path is unchanged.
        val frame = if (outcome != null) replay.current ?: live else live
        return copy(
            position = frame.position,
            checkedKingSquare = frame.checkedKingSquare,
            lastMove = frame.lastMove,
            outcome = outcome,
            frameIndex = replay.index,
            frameCount = if (outcome != null) replay.size else 0,
        )
    }

    /** Steps the finished-game board one position back or forward, stopping at either end. */
    fun onStepReplay(forward: Boolean) {
        if (!replay.step(forward)) return
        _state.update { it.withCurrentBoard() }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (!hasLoadedInitialState) {
            hasLoadedInitialState = true
            observeLiveEvents()
            // Connect first so the socket is live before taps are enabled (the board stays
            // in its "Loading…" state, which blocks taps, until loadExistingGame finishes) —
            // this guarantees even white's first move goes out over the socket.
            viewModelScope.launch(Dispatchers.IO) {
                connect()
                loadExistingGame()
            }
        } else {
            // Returning to foreground: if we were waiting for the opponent, a move may have
            // landed while we were backgrounded — re-sync before reconnecting the socket.
            // A finished game has nothing left to sync or listen for.
            viewModelScope.launch(Dispatchers.IO) {
                if (_state.value.outcome != null) return@launch
                if (isWaitingForOpponent()) loadExistingGame()
                connect()
            }
        }
    }

    override fun onAppPause() {
        super.onAppPause()
        // Drop the live connection while backgrounded; it's re-established on the next show.
        viewModelScope.launch { transport.disconnect() }
    }

    // Loads and replays this game's recorded moves so resuming an in-progress game
    // continues from its real current position instead of the initial one. A brand
    // new game has no server record yet, so a failure here just means "start fresh".
    // Safe to call again (foreground re-sync): the board is rebuilt from scratch.
    private suspend fun loadExistingGame() {
        val playerId = identityStore.getOrCreate()
        // Keep the seeded color / last-known seats until the server tells us otherwise (a
        // transient fetch failure shouldn't clobber what we already knew, e.g. flip an
        // already-filled game back to "shareable").
        var resolvedColor = _state.value.localColor
        var whiteId: String? = _state.value.whitePlayerId
        var blackId: String? = _state.value.blackPlayerId
        var actions = GameActionState(_state.value.drawOffer, agreedDraw)
        api.fetchGame(gameId).onSuccess { detail ->
            // A draw or resign entry shares the number of the move it precedes, so the number
            // alone doesn't order the log. The ISO-8601 timestamp breaks that tie — an action is
            // always written before the move answering it — and the sort is stable beyond it.
            // This is the order the backend serves too (see GameController.allMoves).
            val sorted = detail.moves.sortedWith(compareBy({ it.moveNumber }, { it.timestamp }))
            board = Board()
            lastMove = null
            // The board is rebuilt from scratch here, so the replay is too — starting from the
            // opening position, which no move produced and nothing else would record.
            replay.clear()
            replay.record(currentFrame())
            sorted.forEach { replayMove(it) }
            actions = gameActionState(sorted, playerId)
            whiteId = detail.game.whitePlayerID
            blackId = detail.game.blackPlayerID
            // The log is authoritative about how the game ended: these two feed withCurrentBoard,
            // and re-reading them is what corrects an outcome we applied optimistically on a frame
            // that never actually made it out (transport.send only logs its failures).
            resignedColor = sorted.resignedColor(whiteId, blackId)
            agreedDraw = actions.agreedDraw
            // The record is authoritative: we're white iff we're the white player, else
            // black (a black creator, or a not-yet-joined invitee whose seat is still open).
            resolvedColor =
                if (samePlayer(playerId, detail.game.whitePlayerID)) Piece.Color.white else Piece.Color.black
        }.onFailure { error ->
            Log.w(TAG, "No existing state loaded for game $gameId, starting fresh", error)
        }
        _state.update {
            it.withCurrentBoard().copy(
                localColor = resolvedColor,
                isLoading = false,
                playerId = playerId,
                whitePlayerId = whiteId,
                blackPlayerId = blackId,
                drawOffer = actions.drawOffer,
                // The board was just rebuilt from scratch, so a Pending here would hold a Move
                // belonging to a Board that no longer exists. The move was never sent — the pawn
                // is back a rank short, and it's simply re-playable.
                promotion = Promotion.Hidden,
                notifications = if (actions.drawOffer is DrawOffer.Incoming) {
                    Notifications.Shown
                } else {
                    Notifications.Hidden
                },
            )
        }
        // Revisiting a game that's already over: there's nothing left to hear, so don't hold a
        // socket open for it. A game that ends *while* we're watching keeps its connection —
        // harmless, and it's the path the opponent's resignation would still arrive on.
        if (_state.value.outcome != null) transport.disconnect()
    }

    // No-op once the game is over: a finished game has nothing left to send or hear, and every
    // path that reconnects (first show, foreground, resync) can reach here after it ended.
    private suspend fun connect() {
        if (_state.value.outcome != null) return
        val playerId = _state.value.playerId ?: identityStore.getOrCreate()
        transport.connect(gameId, playerId)
    }

    // Waiting for the opponent means it's not our turn — i.e. the side to move
    // isn't the color this device plays.
    private fun isWaitingForOpponent(): Boolean =
        _state.value.position.sideToMove != _state.value.localColor

    // Collects inbound live actions for the lifetime of the screen. Runs on the main
    // dispatcher, the same context as onSquareTapped, so board mutations stay serialized.
    private fun observeLiveEvents() {
        viewModelScope.launch {
            transport.events.collect { event ->
                when (event) {
                    is LiveEvent.Action -> applyIncoming(event.action)
                    LiveEvent.NeedsResync -> {
                        // Divergence, or the socket dropped — re-sync from the server, then
                        // reconnect (after a short backoff to avoid hammering a bad connection).
                        loadExistingGame()
                        delay(1000)
                        connect()
                    }
                }
            }
        }
    }

    // Applies a live action to our copy of the game. The server never echoes our own frames
    // back, so in practice every one of these is the opponent's — but the actor is checked
    // against our id anyway (case-insensitively; the server echoes ids uppercase), so a frame
    // that did carry our own id can't be mistaken for theirs and demand an answer.
    private fun applyIncoming(action: LiveAction) {
        val mine = samePlayer(action.player, _state.value.playerId)
        // `action.n` needs no tracking: every number we send is derived from the position the
        // frame leaves us on, so there is no running sequence to keep in step. See halfMoveNumber.

        when (action.intent) {
            LiveAction.INTENT_MOVE -> applyIncomingMove(action)

            LiveAction.INTENT_OFFER_DRAW -> _state.update {
                it.copy(
                    drawOffer = if (mine) DrawOffer.Outgoing else DrawOffer.Incoming,
                    // Their offer *is* the notification — raise it rather than wait to be found.
                    notifications = if (mine) it.notifications else Notifications.Shown,
                    selectedSquare = null,
                    legalTargets = emptySet(),
                )
            }

            LiveAction.INTENT_ACCEPT_DRAW -> applyAgreedDraw()

            LiveAction.INTENT_DECLINE_DRAW -> _state.update { it.copy(drawOffer = DrawOffer.Declined) }

            LiveAction.INTENT_RESIGN -> applyResignation(action.player)

            else -> Unit
        }
    }

    // Applies the opponent's live move to the board so we see it appear immediately.
    private fun applyIncomingMove(action: LiveAction) {
        val lan = action.lan ?: return
        if (lan.length < 4) return

        val from = Square(lan.substring(0, 2))
        val to = Square(lan.substring(2, 4))
        val move = board.move(pieceAt = from, to = to) ?: return
        lastMove = move
        if (lan.length == 5 && board.state is Board.State.promotion) {
            Piece.Kind.fromRawValue(lan.last().uppercase())?.let { kind ->
                lastMove = board.completePromotion(move, kind)
            }
        }
        _state.update {
            it.withCurrentBoard().copy(
                selectedSquare = null,
                legalTargets = emptySet(),
            )
        }
    }

    // The opponent gave up while we're watching. The frame moves no pieces — it names a player —
    // so the board is left exactly as it stands and only the outcome changes, which is the whole
    // difference between a resignation and every other way a game ends. An unrecognized player is
    // ignored rather than ending the game on a frame we can't attribute to a seat.
    private fun applyResignation(playerId: String) {
        val current = _state.value
        resignedColor = colorOfPlayer(playerId, current.whitePlayerId, current.blackPlayerId) ?: return
        _state.update { it.withCurrentBoard().endedGame() }
    }

    // Both players settled on a draw. Like a resignation the frame moves no pieces, so the board
    // is left exactly as it stands; unlike every other draw, chesskit can't see this one coming,
    // which is why agreement is carried alongside the board rather than read off it.
    private fun applyAgreedDraw() {
        agreedDraw = true
        _state.update { it.withCurrentBoard().endedGame() }
    }

    // A finished game has nothing left to decide, share or play, so every overlay comes down and
    // the board's selection with it. Every path that ends a game goes through here, so none of
    // them can leave a pane painted over the result.
    private fun State.endedGame(): State = copy(
        drawOffer = DrawOffer.None,
        promotion = Promotion.Hidden,
        notifications = Notifications.Hidden,
        share = Share.Hidden,
        selectedSquare = null,
        legalTargets = emptySet(),
    )

    // Applies a previously recorded move's LAN to [board], completing promotions
    // where needed. Draw-offer/resign log entries carry no LAN and are skipped.
    private fun replayMove(stored: dev.neoneon.flamingo.Move) {
        val lan = stored.lan ?: return   // draw/resign log entries carry no LAN
        if (lan.length < 4) return

        val from = Square(lan.substring(0, 2))
        val to = Square(lan.substring(2, 4))
        val move = board.move(pieceAt = from, to = to) ?: return
        lastMove = move

        if (lan.length == 5 && board.state is Board.State.promotion) {
            Piece.Kind.fromRawValue(lan.last().uppercase())?.let { kind ->
                lastMove = board.completePromotion(move, kind)
            }
        }

        // The replay is built here rather than at the single publish point, because this runs in
        // a loop that publishes once at the end — by which time every intermediate position the
        // player wants to step back through is already gone.
        replay.record(currentFrame())
    }

    // Taps only move our own pieces: they're ignored unless it's this device's
    // color to move, so the opponent's turn can never be played locally.
    fun onSquareTapped(square: Square) {
        _state.update { current ->
            val selected = current.selectedSquare

            when {
                // Anything waiting on a decision owns the screen: poking the board brings its
                // pane back up rather than silently doing nothing. Has to come first — the
                // guards below would otherwise swallow the tap (mid-promotion chesskit has
                // already flipped sideToMove, so it reads as the opponent's turn).
                current.hasPendingNotification -> current.copy(
                    notifications = Notifications.Shown,
                    selectedSquare = null,
                    legalTargets = emptySet(),
                )

                // A finished board is done being played. Without this, a mated player could still
                // select their pieces — every one of them with no legal target.
                current.outcome != null -> current

                current.isLoading || current.position.sideToMove != current.localColor -> current

                selected == square -> current.copy(selectedSquare = null, legalTargets = emptySet())

                selected != null && square in current.legalTargets -> {
                    val preMoveFen = board.position.fen
                    val move = board.move(pieceAt = selected, to = square)
                    move?.let { lastMove = it }
                    // A pawn reaching the last rank leaves the board mid-move: hold the move back
                    // until onPromotionSelected completes it, so its LAN carries the chosen piece.
                    val pending = move
                        ?.takeIf { board.state is Board.State.promotion }
                        ?.let { Promotion.Pending(it, preMoveFen) }
                    if (move != null && pending == null) submitMove(move, preMoveFen, current.playerId)
                    current.withCurrentBoard().copy(
                        selectedSquare = null,
                        legalTargets = emptySet(),
                                promotion = pending ?: Promotion.Hidden,
                        drawOffer = if (move != null) {
                            current.drawOffer.clearedByOurMove()
                        } else {
                            current.drawOffer
                        },
                        // A promotion needs a piece picked before the move can go out, so put the
                        // picker up rather than leaving the player to find the bell.
                        notifications = if (pending != null) Notifications.Shown else current.notifications,
                    )
                }

                current.position.piece(at = square)?.color == current.position.sideToMove -> {
                    current.copy(
                        selectedSquare = square,
                        legalTargets = board.legalMoves(forPieceAt = square).toSet(),
                    )
                }

                else -> current.copy(selectedSquare = null, legalTargets = emptySet())
            }
        }
    }

    // Finishes a promotion the player has now chosen a piece for. Only after this does the move
    // go out, carrying the five-character LAN (e.g. "e7e8q") that the opponent's applyIncoming
    // and our own replayMove both expect.
    fun onPromotionSelected(kind: Piece.Kind) {
        val current = _state.value
        val pending = current.promotion as? Promotion.Pending ?: return

        // A re-sync may have rebuilt `board` under us (see loadExistingGame), leaving this Move
        // pointing at a board that no longer exists — completing against the new one would be
        // meaningless. Drop the stale pending state and let the player play the pawn again.
        if (board.state !is Board.State.promotion) {
            _state.update { it.withCurrentBoard().copy(promotion = Promotion.Hidden) }
            return
        }

        // Completing and sending happen outside `update`, whose lambda can re-run under
        // contention with the socket coroutine — neither is safe to do twice.
        val completed = board.completePromotion(pending.move, kind)
        lastMove = completed
        submitMove(completed, pending.preMoveFen, current.playerId)

        _state.update {
            val resolved = it.copy(
                promotion = Promotion.Hidden,
                drawOffer = it.drawOffer.clearedByOurMove(),
            )
            resolved.withCurrentBoard().copy(
                // An offer can land while the picker is up — our side has already moved as far
                // as chesskit is concerned — so only close the pane if nothing else is waiting.
                notifications = if (resolved.hasPendingNotification) {
                    Notifications.Shown
                } else {
                    Notifications.Hidden
                },
            )
        }
    }

    // Sends the local move over the live socket instead of a separate HTTP request.
    // The frame carries only the acting player's id, so it works the same whether we
    // play white or black: the server records the move (creating the game lazily on
    // white's move 1) and broadcasts it to the opponent. The pre-move `fen` is sent so
    // the server stores the FEN each move was played *from*, matching the existing protocol.
    private fun submitMove(move: Move, preMoveFen: String, playerId: String?) {
        // The pre-move position names the ply being played, so it is also its number.
        val moveNumber = halfMoveNumber(preMoveFen)

        viewModelScope.launch(Dispatchers.IO) {
            val callerPlayerId = playerId ?: identityStore.getOrCreate()
            transport.send(
                LiveAction(
                    intent = LiveAction.INTENT_MOVE,
                    player = callerPlayerId,
                    lan = move.lan,
                    fen = preMoveFen,
                    n = moveNumber,
                )
            )
        }
    }

    // Our own move supersedes an offer we're still awaiting a reply to, and retires the news
    // that one was declined — but it never discards an offer we still owe an answer to.
    private fun DrawOffer.clearedByOurMove(): DrawOffer =
        if (this is DrawOffer.Incoming) this else DrawOffer.None

    /**
     * Sends a draw or resign action over the same socket the moves use.
     *
     * There is no `lan` — the server records the action as a move-log entry carrying only the
     * actor — but `fen` and `n` travel as they do on a move, so the live path writes the same
     * history the HTTP one would. `fen` is the current position, there being no move for it to
     * be "pre".
     *
     * `n` comes from that current position, so an action takes the number of the move it precedes
     * and shares that slot with it — and with any other action answering the same offer. The
     * backend keeps one row per *played* ply and lets actions sit alongside, telling them apart by
     * their actor, so nothing here is swallowed. See [halfMoveNumber].
     */
    private fun sendGameAction(intent: String) {
        val fen = board.position.fen
        val moveNumber = halfMoveNumber(fen)
        val playerId = _state.value.playerId

        viewModelScope.launch(Dispatchers.IO) {
            transport.send(
                LiveAction(
                    intent = intent,
                    player = playerId ?: identityStore.getOrCreate(),
                    fen = fen,
                    n = moveNumber,
                )
            )
        }
    }

    /** The bell: shows the pane, or puts it away again. */
    fun toggleNotifications() {
        _state.update {
            it.copy(
                notifications = if (it.notifications is Notifications.Hidden) {
                    Notifications.Shown
                } else {
                    Notifications.Hidden
                },
            )
        }
    }

    fun closeNotifications() {
        _state.update { it.copy(notifications = Notifications.Hidden) }
    }

    // Each of these applies its own outcome locally before sending: the server broadcasts our
    // frames to the opponent but never echoes them back, so nothing else would ever update us.
    fun offerDraw() {
        if (!_state.value.canOfferDraw) return
        _state.update { it.copy(drawOffer = DrawOffer.Outgoing) }
        sendGameAction(LiveAction.INTENT_OFFER_DRAW)
    }

    fun acceptDraw() {
        if (_state.value.drawOffer !is DrawOffer.Incoming) return
        applyAgreedDraw()
        sendGameAction(LiveAction.INTENT_ACCEPT_DRAW)
    }

    fun declineDraw() {
        if (_state.value.drawOffer !is DrawOffer.Incoming) return
        _state.update { it.copy(drawOffer = DrawOffer.None, notifications = Notifications.Hidden) }
        sendGameAction(LiveAction.INTENT_DECLINE_DRAW)
    }

    /** Resigning is one tap from losing the game, so it asks first. */
    fun requestResign() {
        if (!_state.value.canResign) return
        _state.update { it.copy(notifications = Notifications.ConfirmingResign) }
    }

    fun cancelResign() {
        _state.update { it.copy(notifications = Notifications.Shown) }
    }

    fun confirmResign() {
        val current = _state.value
        if (!current.canResign) return
        // Through the same path an opponent's resignation takes, with our own id: the outcome
        // names the color that gave up, so which side of the socket it arrived from doesn't
        // matter. canResign has already established we hold a seat.
        current.playerId?.let { applyResignation(it) } ?: return
        sendGameAction(LiveAction.INTENT_RESIGN)
    }

    // Opens the share overlay and fetches the phrase from the server (which re-mints if the
    // old one expired), so we never persist it locally. Only meaningful while a seat is open.
    fun openShare() {
        _state.update { it.copy(share = Share.Loading) }
        viewModelScope.launch(Dispatchers.IO) {
            api.shareInvite(gameId).fold(
                onSuccess = { response -> _state.update { it.copy(share = Share.Shown(response.phrase)) } },
                onFailure = { error ->
                    _state.update { it.copy(share = Share.Error(error.message ?: "Couldn't get code")) }
                },
            )
        }
    }

    fun closeShare() {
        _state.update { it.copy(share = Share.Hidden) }
    }

    override fun onCleared() {
        super.onCleared()
        transport.close()
        api.close()
    }
}

class GameView(
    sealedActivity: SealedLightActivity,
    private val gameId: String,
    // white when created via "New game", black when opened from an accepted invite;
    // reconciled with the server's record once the game loads.
    private val initialColor: Piece.Color = Piece.Color.white,
) : LightScreen<Unit, GameViewViewModel>(sealedActivity) {

    override val viewModelClass: Class<GameViewViewModel>
        get() = GameViewViewModel::class.java

    override fun createViewModel() = GameViewViewModel(
        gameId,
        PlayerIdentityStore(lightContext.dataStore),
        LastMoveVisibilityStore(lightContext.dataStore),
        initialColor,
    )

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()

        GameScreen(
            state = state,
            colors = themeColors,
            actions = GameActions(
                onBack = { goBack() },
                onOpenShare = { viewModel.openShare() },
                onCloseShare = { viewModel.closeShare() },
                onSquareTap = { viewModel.onSquareTapped(it) },
                onPromotionSelected = { viewModel.onPromotionSelected(it) },
                onToggleNotifications = { viewModel.toggleNotifications() },
                onCloseNotifications = { viewModel.closeNotifications() },
                onOfferDraw = { viewModel.offerDraw() },
                onAcceptDraw = { viewModel.acceptDraw() },
                onDeclineDraw = { viewModel.declineDraw() },
                onRequestResign = { viewModel.requestResign() },
                onCancelResign = { viewModel.cancelResign() },
                onConfirmResign = { viewModel.confirmResign() },
                onStepReplay = { viewModel.onStepReplay(it) },
            ),
        )
    }
}

/**
 * The game screen's callbacks, grouped so [GameScreen] and its previews stay legible — there are
 * a dozen of them now. The no-op defaults are what let a preview pass state alone.
 */
private data class GameActions(
    val onBack: () -> Unit = {},
    val onOpenShare: () -> Unit = {},
    val onCloseShare: () -> Unit = {},
    val onSquareTap: (Square) -> Unit = {},
    val onPromotionSelected: (Piece.Kind) -> Unit = {},
    val onToggleNotifications: () -> Unit = {},
    val onCloseNotifications: () -> Unit = {},
    val onOfferDraw: () -> Unit = {},
    val onAcceptDraw: () -> Unit = {},
    val onDeclineDraw: () -> Unit = {},
    val onRequestResign: () -> Unit = {},
    val onCancelResign: () -> Unit = {},
    val onConfirmResign: () -> Unit = {},
    val onStepReplay: (Boolean) -> Unit = {},
)

// The whole screen, driven only by [state] and callbacks so every state it can be in — a pending
// promotion, a draw offer, a finished game — is renderable from a @Preview (see the bottom of
// this file).
@Composable
private fun GameScreen(
    state: GameViewViewModel.State,
    colors: LightColors,
    actions: GameActions = GameActions(),
) {
    LightTheme(colors = colors) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            when {
                // A finished game gets no pane of its own: GameContent already presents the
                // ending on the nav bar with the move it ended on shaded, and the board
                // is the thing worth looking at. endedGame() takes the overlays down for it.
                //
                // The notifications pane outranks the share one: it can be holding a decision
                // that's freezing the board, while share is only ever opened deliberately.
                state.notifications !is GameViewViewModel.Notifications.Hidden -> NotificationsPane(
                    state = state,
                    actions = actions,
                )

                state.share !is GameViewViewModel.Share.Hidden -> SharePane(
                    share = state.share,
                    onClose = actions.onCloseShare,
                )

                else -> GameContent(state = state, actions = actions)
            }
        }
    }
}

@Composable
private fun ColumnScope.GameContent(
    state: GameViewViewModel.State,
    actions: GameActions,
) {
    // It's the opponent's turn whenever the side to move isn't the color this device plays —
    // but a finished game isn't waiting on anyone.
    val outcome = state.outcome
    val waiting = !state.isLoading && outcome == null && state.position.sideToMove != state.localColor

    LightTopBar(
        leftButton = LightBarButton.LightIcon(
            icon = LightIcons.BACK,
            onClick = actions.onBack,
            contentDescription = "Back to games",
        ),
        // How the game ended, what's waiting on the player, and whose turn it is all share the nav
        // bar's second line rather than each getting a strip of their own — the bar reserves its
        // height regardless, so none of them costs the board any vertical space.
        //
        // The pending lines outrank `waiting`, which is otherwise a flat lie mid-promotion:
        // chesskit flips the side to move the instant the pawn lands, so the bar would read
        // "Waiting for opponent…" while it is really waiting for you.
        center = when {
            // Once the player steps off the final position the outcome is no longer what the
            // board shows, so the line reports where they are instead of restating the result.
            outcome != null && state.canStepForward ->
                LightTopBarCenter.TwoLineDetail(line1 = "Game", line2 = state.replayLabel)
            outcome != null -> LightTopBarCenter.TwoLineDetail(line1 = "Game", line2 = outcome.label)
            state.promotion is GameViewViewModel.Promotion.Pending ->
                LightTopBarCenter.TwoLineDetail(line1 = "Game", line2 = "Pick a piece to finish")
            state.drawOffer is DrawOffer.Incoming ->
                LightTopBarCenter.TwoLineDetail(line1 = "Game", line2 = "Draw offered")
            waiting ->
                LightTopBarCenter.TwoLineDetail(line1 = "Game", line2 = "Waiting for opponent…")
            else -> LightTopBarCenter.Text("Game")
        },
        // The bar has one right slot and three claims on it. A finished game takes neither button:
        // there's nothing left to decide, and a game someone resigned out of can still have an
        // empty seat but no one worth inviting into it. Otherwise the bell and the invite are near
        // enough mutually exclusive — a draw needs an opponent, and the invite exists precisely
        // because there isn't one yet. The exception is worth spelling out: white can play, and
        // promote, before anyone joins, and a pane you can't re-open is a dead end.
        rightButton = when {
            state.isLoading || outcome != null -> null
            state.hasPendingNotification || !state.hasOpenSeat -> LightBarButton.LightIcon(
                icon = LightIcons.ALARM,
                onClick = actions.onToggleNotifications,
                contentDescription = if (state.promotion is GameViewViewModel.Promotion.Pending) {
                    "Finish promotion"
                } else {
                    "Notifications"
                },
            )
            else -> LightBarButton.LightIcon(
                icon = LightIcons.SEND,
                onClick = actions.onOpenShare,
                contentDescription = "Share invite code",
            )
        },
    )

    // Size the board to the smaller of the available width/height so the full 8×8 always fits
    // below the nav bar, whatever the device's proportions. Pin it to the bottom so any slack
    // falls as flexible breathing room between the board and the nav bar.
    BoxWithConstraints(
        modifier = Modifier
            .weight(1f)
            .fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (state.isLoading) {
            LightText(
                text = "Loading…",
                variant = LightTextVariant.Copy,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            // Tapping a square is a no-op while it's not our turn, and once the game is over
            // (GameViewViewModel.onSquareTapped guards on both), so the board can stay on screen
            // instead of being swapped for text.
            ChessBoard(
                position = state.position,
                selectedSquare = state.selectedSquare,
                legalTargets = state.legalTargets,
                onSquareTap = actions.onSquareTap,
                orientation = state.localColor,
                boardSize = minOf(maxWidth, maxHeight),
                checkedKingSquare = state.checkedKingSquare,
                // Always shown once the game is over — stepping back through it, the marker
                // follows each move in turn. Live, it's gated by the player's own setting.
                lastMove = state.lastMove.takeIf {
                    outcome != null || shouldShowLiveLastMove(
                        visibility = state.lastMoveVisibility,
                        lastMove = state.lastMove,
                        position = state.position,
                        localColor = state.localColor,
                    )
                },
            )
        }
    }

    // A finished game has nothing to decide and no turn to wait for, so the bar the live game
    // never uses is free to step through it. The label sits on the buttons' own row rather than
    // taking height from the board. A button with nowhere to go is left as an empty slot, so the
    // other one stays where it was.
    if (state.canReplay) {
        LightBottomBar(
            items = listOf(
                LightBarButton.Text(text = "PREV", onClick = { actions.onStepReplay(false) })
                    .takeIf { state.canStepBack },
                LightBarButton.Text(text = "NEXT", onClick = { actions.onStepReplay(true) })
                    .takeIf { state.canStepForward },
            ),
        )
    }
}

// The share overlay, shown in place of the board while [share] is non-Hidden. Reuses the
// same phrase panel as the create flow (see SharePhraseContent).
@Composable
private fun ColumnScope.SharePane(share: GameViewViewModel.Share, onClose: () -> Unit) {
    LightTopBar(
        leftButton = LightBarButton.LightIcon(
            icon = LightIcons.BACK,
            onClick = onClose,
            contentDescription = "Back to game",
        ),
        center = LightTopBarCenter.Text("Invite"),
    )
    when (share) {
        GameViewViewModel.Share.Loading -> CenteredMessage("Getting code…")
        is GameViewViewModel.Share.Shown -> SharePhraseContent(
            phrase = share.phrase,
            buttonLabel = "BACK TO GAME",
            onDone = onClose,
        )
        is GameViewViewModel.Share.Error -> CenteredMessage(share.message)
        GameViewViewModel.Share.Hidden -> Unit // never rendered by SharePane
    }
}

/**
 * The one pane the bell opens: whatever is currently waiting on the player.
 *
 * A pending promotion outranks a draw offer — it's holding back a move of ours — and both
 * outrank the idle offer-draw / resign actions. Always dismissible; see [GameViewViewModel.Promotion]
 * for why that's safe even mid-promotion.
 */
@Composable
private fun ColumnScope.NotificationsPane(
    state: GameViewViewModel.State,
    actions: GameActions,
) {
    LightTopBar(
        leftButton = LightBarButton.LightIcon(
            icon = LightIcons.BACK,
            onClick = actions.onCloseNotifications,
            contentDescription = "Back to game",
        ),
        center = LightTopBarCenter.Text(notificationsTitle(state)),
    )

    val promotion = state.promotion
    when {
        promotion is GameViewViewModel.Promotion.Pending -> PromotionOptions(
            color = promotion.move.piece.color,
            square = promotion.move.end,
            onSelect = actions.onPromotionSelected,
        )

        state.notifications is GameViewViewModel.Notifications.ConfirmingResign -> {
            CenteredMessage("You will lose the game.")
            LightBottomBar(
                items = listOf(
                    LightBarButton.Text(text = "CANCEL", onClick = actions.onCancelResign),
                    LightBarButton.Text(text = "RESIGN", onClick = actions.onConfirmResign),
                ),
            )
        }

        state.drawOffer is DrawOffer.Incoming -> {
            CenteredMessage("Your opponent offers a draw.")
            LightBottomBar(
                items = listOf(
                    LightBarButton.Text(text = "DECLINE", onClick = actions.onDeclineDraw),
                    LightBarButton.Text(text = "ACCEPT", onClick = actions.onAcceptDraw),
                ),
            )
        }

        state.drawOffer is DrawOffer.Outgoing -> CenteredMessage("Draw offered.\nWaiting for a reply.")

        state.drawOffer is DrawOffer.Declined -> CenteredMessage("Your draw offer was declined.")

        else -> ActionOptions(state = state, actions = actions)
    }
}

// Titling the pane with the section beats a generic "Notifications": it says what the player is
// looking at before they've read a word of the body.
private fun notificationsTitle(state: GameViewViewModel.State): String = when {
    state.promotion is GameViewViewModel.Promotion.Pending -> "Promote to"
    state.notifications is GameViewViewModel.Notifications.ConfirmingResign -> "Resign?"
    state.drawOffer is DrawOffer.None -> "Game actions"
    else -> "Draw offer"
}

// What the player can start from here when nothing is waiting on them.
@Composable
private fun ColumnScope.ActionOptions(
    state: GameViewViewModel.State,
    actions: GameActions,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalArrangement = Arrangement.Center,
    ) {
        ActionRow(label = "Offer draw", enabled = state.canOfferDraw, onClick = actions.onOfferDraw)
        ActionRow(label = "Resign", enabled = state.canResign, onClick = actions.onRequestResign)
    }
}

// MARK: Previews

// Builds the state a real promotion leaves behind by actually playing one, rather than
// hand-assembling a Move — so the previews can't drift from what the board really produces.
private fun promotingState(): GameViewViewModel.State {
    val board = Board(Position("k7/7P/8/8/8/8/8/K7 w - - 0 1")!!)
    val preMoveFen = board.position.fen
    val move = board.move(pieceAt = Square.h7, to = Square.h8)!!

    return GameViewViewModel.State(
        position = board.position,
        localColor = Piece.Color.white,
        isLoading = false,
        promotion = GameViewViewModel.Promotion.Pending(move, preMoveFen),
        // A promotion raises the pane on its own — see onSquareTapped.
        notifications = GameViewViewModel.Notifications.Shown,
    )
}

// A game a few moves in with both seats filled: what every draw/resign preview starts from,
// since none of those actions is available until there's an opponent to make them to.
private fun playedGameState(): GameViewViewModel.State {
    val board = Board()
    board.move(pieceAt = Square.e2, to = Square.e4)
    val last = board.move(pieceAt = Square.e7, to = Square.e5)!!

    return GameViewViewModel.State(
        position = board.position,
        localColor = Piece.Color.white,
        isLoading = false,
        playerId = "us",
        whitePlayerId = "us",
        blackPlayerId = "them",
        lastMove = last.start to last.end,
    )
}

// Likewise for check: the marker is derived from the board's own state after a real checking move
// (Rg1-h1+), seen from the checked player's side so the board is also flipped.
private fun checkedState(): GameViewViewModel.State {
    val board = Board(Position("7k/8/8/8/8/8/8/K5R1 w - - 0 1")!!)
    board.move(pieceAt = Square.g1, to = Square.h1)

    return GameViewViewModel.State(
        position = board.position,
        localColor = Piece.Color.black,
        isLoading = false,
        checkedKingSquare = checkedKingSquare(board.state, board.position),
    )
}

// The two ways a game ends, both built from a real board so the shaded squares and the outcome line
// come from the same code the screen runs. Fool's mate leaves black's queen on h4, with d8 and h4
// shaded and the mated white king underlined on e1.
private fun matedState(): GameViewViewModel.State {
    val board = Board()
    var last: Move? = null
    for (lan in listOf("f2f3", "e7e5", "g2g4", "d8h4")) {
        last = board.move(pieceAt = Square(lan.substring(0, 2)), to = Square(lan.substring(2, 4)))
    }

    return GameViewViewModel.State(
        position = board.position,
        localColor = Piece.Color.white,
        isLoading = false,
        checkedKingSquare = checkedKingSquare(board.state, board.position),
        lastMove = last!!.let { it.start to it.end },
        outcome = gameOutcome(board.state, resignedColor = null),
    )
}

// A resignation instead: the position is unremarkable and nobody is in check, so the nav bar's
// second line is the only thing saying the game is over — and there's still a last move to point at.
private fun resignedState(): GameViewViewModel.State {
    val board = Board()
    val move = board.move(pieceAt = Square.e2, to = Square.e4)!!

    return GameViewViewModel.State(
        position = board.position,
        localColor = Piece.Color.white,
        isLoading = false,
        lastMove = move.start to move.end,
        outcome = gameOutcome(board.state, resignedColor = Piece.Color.black),
    )
}

// Every preview renders in each theme via the SDK's provider — the clipped radio dots in the "New
// game" picker were a light-theme-only bug, so seeing both at once is worth the parameter.

// The whole game screen as it looks mid-promotion, dispatched from State exactly as Content() does.
@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewGameViewPromoting(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    GameScreen(state = promotingState(), colors = colors)
}

// The same promotion with the pane put away — the state the bell makes reachable. Worth its own
// preview: it's the one place the board shows a pawn sitting unpromoted on the last rank, and the
// nav bar has to say why rather than claiming we're waiting on the opponent.
@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewGameViewPromotionDismissed(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    GameScreen(
        state = promotingState().copy(notifications = GameViewViewModel.Notifications.Hidden),
        colors = colors,
    )
}

// An offer from the opponent, which raises the pane by itself.
@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewGameViewDrawOffered(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    GameScreen(
        state = playedGameState().copy(
            drawOffer = DrawOffer.Incoming,
            notifications = GameViewViewModel.Notifications.Shown,
        ),
        colors = colors,
    )
}

// The pane as the bell opens it with nothing pending: the two actions the player can start.
@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewGameViewActions(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    GameScreen(
        state = playedGameState().copy(notifications = GameViewViewModel.Notifications.Shown),
        colors = colors,
    )
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewGameViewResignConfirm(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    GameScreen(
        state = playedGameState().copy(notifications = GameViewViewModel.Notifications.ConfirmingResign),
        colors = colors,
    )
}

// The one ending chesskit can't see coming, so the only one that needs agreement passed in: an
// ordinary position, nobody in check, and the nav bar's second line the only thing saying it's
// over. Checkmate and resignation have previews of their own above.
@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewGameViewDrawAgreed(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    val played = playedGameState()
    GameScreen(
        state = played.copy(
            outcome = gameOutcome(Board.State.active, resignedColor = null, agreedDraw = true),
        ),
        colors = colors,
    )
}

// The board with the king in check: a line along the bottom edge of that one square. It renders at
// full width here — the left-to-right draw-in is an animation, which previews don't run.
@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewGameViewInCheck(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    GameScreen(state = checkedState(), colors = colors)
}

// A game that ended in mate: "Checkmate, black won" on the nav bar's second line, the from- and
// to-squares of the move that delivered it shaded, and the mated king still underlined beneath it.
@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewGameViewCheckmated(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    GameScreen(state = matedState(), colors = colors)
}

// The same presentation for a game someone resigned, where the board itself gives nothing away.
@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewGameViewResigned(
    @PreviewParameter(LightColorsPreviewProvider::class) colors: LightColors,
) {
    GameScreen(state = resignedState(), colors = colors)
}
