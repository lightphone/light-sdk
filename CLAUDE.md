# CLAUDE.md

This is a fork of Light Phone's `light-sdk` scaffolding. Everything upstream (`sdk/*`,
`plugin`, `lint-rules`, `builder`, `examples`) is **theirs** — the work that happens here
is in the one module we own:

| Module | Ours? | Contents |
|--------|-------|----------|
| `:tool` | yes | The `#flamingo chess` Light Phone III tool, package `dev.neoneon.flamingo`, plus the chess engine in `dev.neoneon.chesskit` (board, legality, FEN/SAN/LAN/PGN parsers — see `docs/chesskit.md`) |
| `:sdk:*`, `:plugin`, `:lint-rules`, `:builder` | no | Upstream scaffolding; keep byte-identical to `light/main` |

The project is `#flamingo chess`, but users see the tool as `Chess` — that is its launcher
`label` in `tool/lighttool.toml`. Keep it `Chess`.

`#flamingo chess` spans three repositories, and a change to the wire contract is a
three-repo change:

| Repo | What lives there |
|------|------------------|
| `q231950/light-sdk` (this one) | The Light Phone III tool and its chess engine |
| `q231950/neoneon` | The Vapor backend at `https://neoneon.dev` — `docs/flamingo-api.md` is the contract |
| `q231950/flamingo` | iOS: iMessage extension, companion app, `FlamingoChessCore` |

## Git

Use [Conventional Commits](https://www.conventionalcommits.org/). Work in this repo is
scoped `(tool)` — e.g. `feat(tool): highlight the king in check`.

## Build and test

```bash
./gradlew check          # every module must compile and every test must pass
./gradlew :tool:test     # tool unit tests only (engine tests included)
./gradlew :tool:testDebugUnitTest --tests 'dev.neoneon.chesskit.*' # engine tests (incl. perft)
./gradlew :tool:assembleDebug
```

### What Light's builder sees

A submission is built by Light's `builder/`, which copies **only** `tool/build.gradle.kts`,
`tool/lighttool.toml` and `tool/src/main/{kotlin,java,res,assets}/**` into a clean copy of
upstream `light-sdk` and builds `--offline` against a Gradle cache warmed by upstream's own
`:tool`. Our `settings.gradle.kts`, version catalog, plugin edits and any extra modules are
thrown away. So:

- Never make the tool depend on another module, a catalog entry, or a plugin change of ours.
  A new library must already be in upstream's catalog *and* reachable from `:sdk:client`, or
  Light has to add it first.
- `git diff light/main --stat -- . ':!tool' ':!docs' ':!CLAUDE.md'` should stay empty.
- `serverPackage` is committed as `com.lightos`; flip it to `com.thelightphone.sdk.emulator`
  locally for the emulator (building with `-DlightSdk.allowAltServerPackage=true`, or the
  plugin fails the build), and don't commit that.
- The Ktor WebSockets plugin ships in `ktor-client-core`; `ktor-client-websockets` is an empty
  stub and must not come back.

`compileSdk 36`, `minSdk 34`, JVM target 17 (set in the root `build.gradle.kts`).
Debug and release both sign with the shared `lightsdk-dev` keystore.

Test on a real Light Phone III, or on an Android emulator running the LightOS emulator app
as a system app (see `docs/system_app`) — push notifications and special permissions only
work there. An LPIII-ish AVD is 1080×1240, 3.92", API 34, no Play Services.

## Platform constraints

LightOS deliberately restricts which Android APIs and third-party libraries a tool may
use, and `:lint-rules` enforces part of that (`RestrictedApi` is an error in `:tool`).
Kotlin + Compose + Coroutines + MVVM only. Before reaching for a library, check that it is
allowed — the safe set is what `:sdk:client` already exposes plus what `tool/build.gradle.kts`
already declares (Room/KSP, lifecycle-viewmodel-compose). Ktor — WebSockets included — comes
through `:sdk:client`.

LightOS has no system navigation. Move between screens with `navigateTo` from `LightScreen`;
the SDK supplies the back button. Screens come in `LightScreen` / `LightViewModel` pairs.

## The tool

`tool/src/main/kotlin/dev/neoneon/flamingo/`

| File | Responsibility |
|------|----------------|
| `ToolEntryPoint` | `@EntryPoint` — LightOS registration data and push notifications |
| `GamesListScreen` / `GameListRow` | The tool's home: the player's games and what their status means |
| `RemoteGameScreen` / `CreateGameScreen` / `JoinByPhraseScreen` / `LightCodeInput` | REMOTE in the Games bottom bar: invite-code create and join, OTP-style per-letter entry |
| `SharePhrase` | Sharing the invite code from inside a game |
| `GameView` / `ChessBoard` | The board, its ViewModel, and rendering (check highlight, last-move square shading) |
| `GameActions` / `GameOutcome` | Draw offer/answer/decline, resign, and how a game ended |
| `GamePanes` | Promotion picker, action row and centered message, shared by `GameView` and `HotSeatScreen` |
| `HotSeatScreen` / `HotSeatGameModel` | "Local": two players on one phone, offline — see Hot seat below |
| `HotSeatGame` / `HotSeatGameStore` (port) / `DataStoreHotSeatGameStore` (adapter) | The one stored hot seat game |
| `HotSeatArchive` / `HotSeatArchiveStore` (port) / `DataStoreHotSeatArchiveStore` (adapter) | Finished hot seat games saved to the archive |
| `ArchiveScreen` / `ArchiveState` / `HotSeatReplayScreen` | The archive, reached from Info — see Archive below — and an archived local game's replay |
| `ReplayLog` | Move history, numbered by half-move to match the backend |
| `FlamingoApi` / `FlamingoModels` | Ktor HTTP client against `https://neoneon.dev/flamingo` |
| `LiveTransport` (port) / `KtorLiveTransport` (adapter) | The live WebSocket |
| `PlayerIdentity` | One player ID per installation, in DataStore, plus its server registration and cached display name |
| `PlayerKeyStore` / `SignedRequestPayload` | The AndroidKeyStore signing key, and the canonical payload every signed request agrees on |
| `AccountScreen` | This install's display name — view and rename, reached from Info |
| `SettingsScreen` / `LastMoveVisibility` | Settings, incl. last-move marking (`Hidden` / `Latest` / `OpponentOnly`) |
| `InfoScreen` | Archive, account, board settings, and legal docs, reached from the ellipsis in the Games top bar |

Keep the transport behind the `LiveTransport` port — `KtorLiveTransport` is the only thing
that should know about Ktor websockets. The iOS side mirrors this split
(`LiveTransport` / `URLSessionLiveTransport`), so the two clients stay comparable.

## Hot seat

Two people, one phone — `LOCAL` in the Games bottom bar, titled "Local" on screen. Ported from iOS
(`flamingo`'s `docs/product/features/00008-hot-seat.md`), keeping its `HotSeat*` names.

- **Local only and single.** No server, no `FlamingoApi`, no `GameViewViewModel`. `LOCAL` is
  shown whatever the service status says. There is at most one hot seat game.
- Stored as JSON under **`FLAMINGO_HOT_SEAT_GAME`** in the tool's DataStore. Nothing is written
  until the first move; unreadable data is cleared. Writes go through one channel in
  `HotSeatViewModel`, so they land in order. It is unbounded, not conflated: a conflated one would
  let the next game's first move swallow a pending archive write.
- Stored as the **move list (LAN), not a FEN**: replaying through one chesskit `Board` keeps its
  repetition counts, so threefold repetition survives a relaunch or a take-back. A stored list
  that stops being legal is cut at the last legal move and saved back.
- chesskit's `Board.move` **doesn't check whose turn it is** — `HotSeatGameModel` does.
- `HotSeatGameModel` is plain Kotlin and holds the rules; take-back and reset rebuild the board
  by replay. BACK on the promotion picker puts the pawn back (nothing was recorded for it).
- **Turning the board is the screen's job**: the model's `orientation` trails the side to move
  (`needsTurn`). 0.8 s after a move the board fades out (0.5 s), `turnToSideToMove()` flips it
  while invisible, and it fades back in (0.5 s). Taps and the actions button wait for it.
- **Save to archive** (first in Game actions, finished games only, asks first) moves the game into
  the archive — JSON list under **`FLAMINGO_HOT_SEAT_ARCHIVE`**, newest first — and starts the
  board over as Reset does. Moved, not copied: a take-back on an archived game would leave two
  versions of it. The outcome is stored as iOS stores it (`whiteWins` / `blackWins` / `draw`), so
  the list never replays a game. Mirrors iOS's `docs/product/features/00009-hot-seat-archive.md`.

## Archive

**Info → Archive** (`ArchiveScreen`), the counterpart of iOS's Settings → Archive: online games
first, then local ones.

- **Online archiving is per seat and lives on the server.** `GET /flamingo/games?archived=true`
  lists it (the live list sends `archived=false`), `POST /flamingo/games/:id/archive` moves a game
  in or out (`FlamingoApi.setArchived`). The opponent's lists never change. Read our flag through
  `Game.isArchivedBy`, which compares with `samePlayer`.
- **Archive** / **Unarchive** is the last row of the game's actions pane, behind the ellipsis at the
  top right (`GameView`; the ellipsis replaced the alarm-clock icon). It is greyed while the game is
  on, and a finished game keeps the ellipsis only while it can be archived. On success the screen
  goes back to the list it came from, which reloads on show.
- Offered only for endings the server holds as finished (`GameOutcome.isRecordedByServer`): mate,
  stalemate, resignation, agreed draw. Repetition, fifty-move and too-few-pieces draws are found by
  chesskit alone and stay `active` on the server, which refuses to archive them (409).
- The online half reloads every time the screen shows and closes with the service (`disabled`
  shows its message in place of the rows). The local half follows its store as a flow and shows
  whatever the status says.
- Local rows open `HotSeatReplayScreen`: final position first, PREV/NEXT, white at the bottom.
  There is no swipe on LightOS, so deleting is the replay's trash button; the replay hands the id
  back as its result and the archive screen deletes it.

## Wire contract

Read `neoneon/docs/flamingo-api.md` before touching `FlamingoApi` or `FlamingoModels`.

Live frames (`LiveAction`), over `wss://neoneon.dev/flamingo/games/<gameId>/socket?pid=<playerId>`:

```json
{ "intent": "move", "player": "<uuid>", "lan": "e2e4", "fen": "<pre-move FEN>", "n": 3 }
```

`intent` ∈ `move | offerDraw | acceptDraw | declineDraw | resign`. The gameId is in the
path and never repeated in the frame. `lan` is present only for moves; `fen` and `n`
travel on every frame — draw/resign included — so the live path writes the same server
history as the HTTP one. For a draw/resign frame, `fen` is the current position and `n` is
the half-move the actor would have played next.

## Gotchas

- **`fen` is the position a move was played *from*, not the one it produced.** That holds
  for the socket frame, the URL contract, and `MoveDTO.fenAfter` (a misnomer kept for wire
  compatibility). The receiver seeds from `fen` and replays `lan` on top.
- **Compare player IDs case-insensitively** (`samePlayer`). This client mints lowercase
  `UUID.randomUUID()` strings; the server round-trips them through a Swift `UUID` and
  echoes them back uppercase. A plain `==` silently resolved every player to black.
- **Editing the name is signed.** `PATCH /flamingo/players/:id` requires a P-256 signature
  from a key generated in the **AndroidKeyStore** (`PlayerKeyStore`); reads are not signed.
  `SignedRequestPayload.canonical` must agree **byte for byte** with neoneon's
  `SignedRequest.swift` and iOS's — the shared fixture in `neoneon/docs/flamingo-api.md` is
  asserted in all three suites, because a mismatch shows up only as an opaque 401. Specified
  in `neoneon/docs/adr/ADR-001-signed-personal-data-edits.md`.
- **The signed `playerId` is upper-cased; the signed path is not.** This client mints
  lowercase `UUID` strings and Swift's `uuidString` is uppercase, so without that one
  normalization the two clients sign different bytes for the same player. Same mismatch
  `samePlayer` exists for, but failing silently instead of loudly.
- **Signed bodies are serialized by hand into a `TextContent`**, never handed to content
  negotiation. `setBody(someString)` under a JSON content type would be re-encoded as a
  quoted JSON string, so the body hash would describe bytes the server never received.
- **The key is device-bound and cannot be migrated.** AndroidKeyStore keys are
  non-extractable and excluded from backup, and Block Store needs Play Services the LPIII
  does not have. A new handset means a new key and a new profile; the Account screen says so.
  (iOS differs — there the key rides iCloud Keychain.) The only migration path is key
  rotation signed by the old device while it still works.
- **Every player has a display name**, so a game is titled `"blueberry764 vs orange489"`
  (`Game.title`, white first). The server mints it; this tool registers its id once
  (`PlayerIdentityStore.ensureRegistered`) and caches the name in DataStore. Names are read
  through `/flamingo/players`, never off the game record, and looked up through a **lower-cased**
  map (`byPlayerId`) for the same case mismatch `samePlayer` exists for. Each seat falls back on
  its own — "open seat" for an unfilled one, the id's first 8 characters for a name that hasn't
  arrived — so a failed lookup never blanks a row.
- **One identity per install, either color.** `PlayerIdentityStore` reuses the legacy
  `FLAMINGO_WHITE_PLAYER_ID` value when present, so games created back when a local game
  was always white aren't orphaned.
- Invite codes are five letters from an alphabet without `I`, `O`, `Q`, `U`, but parsing is
  lenient — normalize entry (uppercase, ASCII letters, truncate), never reject a letter a
  user might read off a screen.
- A game action shares a `moveNumber` with the move answering it; order history by
  `moveNumber` then timestamp. `ReplayLog` numbers entries by half-move to match.
- The socket carries live actions only, with no history replay — re-sync over
  `GET /flamingo/games/:id` before reconnecting after a drop.
- `LiveAction`'s KDoc on the iOS side claims `fen`/`n` are move-only; that comment is
  stale, the behaviour is as described above.
