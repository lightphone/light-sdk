package com.thelightphone.sdk.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class LightResultTest {
    @Test
    fun `ensure error code values have not changed`() {
        LightResult.ErrorCode.entries.forEach {
            val expectedOrdinal = when(it) {
                LightResult.ErrorCode.Unknown -> 0
                LightResult.ErrorCode.Removed -> 1
                LightResult.ErrorCode.InvalidParameters -> 2
                LightResult.ErrorCode.NoPermission -> 3
                LightResult.ErrorCode.InvalidToken -> 4
            }
            assertEquals(expectedOrdinal, it.ordinal, "Ordinal for $it has been changed!")
        }
    }
}