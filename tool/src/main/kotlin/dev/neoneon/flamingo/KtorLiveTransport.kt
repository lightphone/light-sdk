package dev.neoneon.flamingo

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "KtorLiveTransport"
private const val SOCKET_BASE = "wss://neoneon.dev/flamingo"

/**
 * Adapter: implements [LiveTransport] over a Ktor OkHttp WebSocket.
 *
 * Connects to `wss://neoneon.dev/flamingo/games/<gameId>/socket?pid=<playerId>`,
 * serializes outbound [LiveAction]s to JSON, and emits decoded inbound events.
 * The socket carries live actions only — catch-up after a disconnect is the
 * consumer's job (an HTTP re-sync), which is why an unexpected close surfaces as
 * [LiveEvent.NeedsResync].
 *
 * Uses its own dedicated [HttpClient] so the live transport stays fully
 * independent of the HTTP [FlamingoApi].
 */
class KtorLiveTransport(
    private val scope: CoroutineScope,
) : LiveTransport {

    private val client = HttpClient(OkHttp) {
        install(WebSockets)
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val _events = MutableSharedFlow<LiveEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<LiveEvent> = _events

    private var session: DefaultClientWebSocketSession? = null
    private var receiveJob: Job? = null

    override suspend fun connect(gameId: String, playerId: String) {
        disconnect()
        try {
            val newSession = client.webSocketSession("$SOCKET_BASE/games/$gameId/socket?pid=$playerId")
            session = newSession
            receiveJob = scope.launch(Dispatchers.IO) {
                try {
                    for (frame in newSession.incoming) {
                        if (frame is Frame.Text) dispatch(frame.readText())
                    }
                    // Channel closed by the peer/server — ask the consumer to re-sync.
                    if (isActive) _events.emit(LiveEvent.NeedsResync)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "live receive ended", e)
                    if (isActive) _events.emit(LiveEvent.NeedsResync)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "live connect failed for game $gameId", e)
        }
    }

    override suspend fun send(action: LiveAction) {
        val current = session ?: return
        try {
            current.send(Frame.Text(json.encodeToString(action)))
        } catch (e: Exception) {
            Log.e(TAG, "live send failed", e)
        }
    }

    override suspend fun disconnect() {
        receiveJob?.cancel()
        receiveJob = null
        try {
            session?.close()
        } catch (e: Exception) {
            Log.w(TAG, "live disconnect error", e)
        }
        session = null
    }

    override fun close() {
        client.close()
    }

    private suspend fun dispatch(text: String) {
        val action = runCatching { json.decodeFromString<LiveAction>(text) }.getOrNull()
        if (action != null && action.intent in LiveAction.KNOWN_INTENTS) {
            _events.emit(LiveEvent.Action(action))
        } else {
            // An "error" frame (divergence / fen_mismatch / server_error) or any
            // unparseable frame — re-sync from the server's canonical state.
            _events.emit(LiveEvent.NeedsResync)
        }
    }
}
