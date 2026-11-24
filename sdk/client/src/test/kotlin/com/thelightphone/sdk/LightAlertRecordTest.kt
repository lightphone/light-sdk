package com.thelightphone.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LightAlertRecordTest {
    @Test
    fun roundTripsAlertsIncludingDelimiters() {
        val records = listOf(
            LightAlertRecord(
                id = "dose\t1",
                title = "Time for\na meal",
                text = "100%",
                triggerAtMillis = 1_700_000_000_000,
            ),
            LightAlertRecord(
                id = "walk",
                title = "Walk",
                text = "",
                triggerAtMillis = 1_700_000_060_000,
            ),
        )

        assertEquals(records, decodeAlerts(encodeAlerts(records)))
    }

    @Test
    fun dropsACorruptLineAndKeepsTheRest() {
        val raw = encodeAlerts(
            listOf(
                LightAlertRecord("ok", "Ok", "body", 5),
            ),
        )

        val decoded = decodeAlerts("not-a-record\n$raw")

        assertEquals(listOf("ok"), decoded.map { it.id })
    }

    @Test
    fun emptyStoreDecodesToNoAlerts() {
        assertTrue(decodeAlerts("").isEmpty())
    }
}
