package dev.neoneon.flamingo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The canonical payload, and the fixture that proves this client agrees with the other two.
 *
 * Pure string work, so it runs on the JVM. [PlayerKeyStore] needs the AndroidKeyStore and is
 * verified on device instead.
 */
class SignedRequestPayloadTest {

    private val playerIdUpper = "96596872-5FE3-4574-8684-ACA1047AFA23"
    private val playerIdLower = "96596872-5fe3-4574-8684-aca1047afa23"

    /**
     * The shared fixture from `neoneon/docs/flamingo-api.md`. The server suite and the iOS suite
     * assert the same string. If all three agree, a rename signed here verifies there — and a
     * disagreement otherwise surfaces only as an opaque 401, which is miserable to debug from a
     * Light Phone.
     */
    @Test
    fun matchesTheSharedFixture() {
        val payload = SignedRequestPayload.canonical(
            method = "PATCH",
            path = "/flamingo/players/$playerIdUpper",
            playerId = playerIdUpper,
            timestamp = 1758240000L,
            nonce = "11111111-2222-3333-4444-555555555555",
            body = """{"name":"sourdough"}""",
        )

        assertEquals(
            listOf(
                "flamingo-v1",
                "PATCH",
                "/flamingo/players/$playerIdUpper",
                playerIdUpper,
                "1758240000",
                "11111111-2222-3333-4444-555555555555",
                "c3f12947278b94bba375e635fa144ebfe4b658e5f9a2f2b9a99b7dac853bbb0f",
            ).joinToString("\n"),
            payload,
        )

        // Also catches a stray trailing newline or a wrong separator, which the comparison above
        // can hide when the diff is long.
        assertEquals(
            "4aee539c1527d9adadd0c8b6145060d049a94ee23e032b0b5ea7dfce90ae8932",
            SignedRequestPayload.sha256Hex(payload.toByteArray(Charsets.UTF_8)),
        )
    }

    /**
     * The whole reason the player id is normalized: this client mints lowercase UUID strings
     * while Swift's `uuidString` is uppercase, so without this the two would sign different bytes
     * for the same player and both be rejected.
     */
    @Test
    fun upperCasesThePlayerId() {
        val fromLower = SignedRequestPayload.canonical(
            method = "PATCH", path = "/p", playerId = playerIdLower,
            timestamp = 1L, nonce = "n", body = "",
        )
        val fromUpper = SignedRequestPayload.canonical(
            method = "PATCH", path = "/p", playerId = playerIdUpper,
            timestamp = 1L, nonce = "n", body = "",
        )

        assertEquals(fromUpper, fromLower)
        assertEquals(true, fromLower.contains(playerIdUpper))
    }

    /** The path is signed as sent — normalizing it would break agreement with the server, which
     * signs the path it actually received. */
    @Test
    fun doesNotNormalizeThePath() {
        val lowerPath = SignedRequestPayload.canonical(
            method = "PATCH", path = "/flamingo/players/$playerIdLower", playerId = playerIdUpper,
            timestamp = 1L, nonce = "n", body = "",
        )
        val upperPath = SignedRequestPayload.canonical(
            method = "PATCH", path = "/flamingo/players/$playerIdUpper", playerId = playerIdUpper,
            timestamp = 1L, nonce = "n", body = "",
        )

        assertNotEquals(lowerPath, upperPath)
    }

    /**
     * Deleting a game sends no body, so it signs the hash of zero bytes — and sends zero bytes,
     * since `FlamingoApi.deleteGame` never sets a body. The server hashes what it received the
     * same way, so the two agree.
     */
    @Test
    fun aDeleteSignsAnEmptyBody() {
        val gameId = "c0ffee00-0000-4000-8000-000000000001"
        val payload = SignedRequestPayload.canonical(
            method = "DELETE",
            path = "/flamingo/games/$gameId",
            playerId = playerIdLower,
            timestamp = 1758240000L,
            nonce = "11111111-2222-3333-4444-555555555555",
            body = "",
        )

        assertEquals(
            listOf(
                "flamingo-v1",
                "DELETE",
                "/flamingo/games/$gameId",
                playerIdUpper,
                "1758240000",
                "11111111-2222-3333-4444-555555555555",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ).joinToString("\n"),
            payload,
        )
    }

    @Test
    fun hashesAnEmptyBodyAsZeroBytes() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            SignedRequestPayload.sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun upperCasesTheMethod() {
        assertEquals(
            SignedRequestPayload.canonical("PATCH", "/a", playerIdUpper, 1L, "n", ""),
            SignedRequestPayload.canonical("patch", "/a", playerIdUpper, 1L, "n", ""),
        )
    }

    /** Every component must change the payload, or a signature could be lifted onto another
     * request. */
    @Test
    fun everyComponentChangesThePayload() {
        val base = SignedRequestPayload.canonical("PATCH", "/a", playerIdUpper, 100L, "n1", "one")

        val variants = listOf(
            SignedRequestPayload.canonical("POST", "/a", playerIdUpper, 100L, "n1", "one"),
            SignedRequestPayload.canonical("PATCH", "/b", playerIdUpper, 100L, "n1", "one"),
            SignedRequestPayload.canonical("PATCH", "/a", playerIdLower.dropLast(1) + "4", 100L, "n1", "one"),
            SignedRequestPayload.canonical("PATCH", "/a", playerIdUpper, 101L, "n1", "one"),
            SignedRequestPayload.canonical("PATCH", "/a", playerIdUpper, 100L, "n2", "one"),
            SignedRequestPayload.canonical("PATCH", "/a", playerIdUpper, 100L, "n1", "two"),
        )

        variants.forEachIndexed { index, variant ->
            assertNotEquals(base, variant, "component $index did not affect the payload")
        }
    }
}
