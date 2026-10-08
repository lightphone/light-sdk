package com.thelightphone.sdk.nfc

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class LightIsoDepTest {
    @Test
    fun successfulExchangePassesTheResponseThrough() {
        val response = byteArrayOf(0x90.toByte(), 0x00)

        assertContentEquals(response, mapIsoDepFailures { response })
    }

    @Test
    fun ioFailureSaysTheTapDidNotComplete() {
        val cause = IOException("Transceive failed")

        val error = assertFailsWith<LightNfcReadException> {
            mapIsoDepFailures { throw cause }
        }

        assertEquals(READ_FAILED_MESSAGE, error.message)
        assertSame(cause, error.cause)
    }

    @Test
    fun staleTagSaysTheCardMovedAway() {
        val cause = SecurityException("Permission Denial: Tag is out of date")

        val error = assertFailsWith<LightNfcReadException> {
            mapIsoDepFailures { throw cause }
        }

        assertEquals(CARD_LOST_MESSAGE, error.message)
        assertSame(cause, error.cause)
    }

    @Test
    fun otherFailuresAreNotRewritten() {
        assertFailsWith<IllegalStateException> {
            mapIsoDepFailures { throw IllegalStateException("Call connect() first!") }
        }
    }
}
