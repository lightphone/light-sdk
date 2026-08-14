package com.thelightphone.sdk.nfc

import android.nfc.NfcAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LightNfcTypesTest {
    @Test
    fun onlyReadyAvailabilityIsReady() {
        assertTrue(LightNfcAvailability.Ready.isReady)
        assertFalse(LightNfcAvailability.Disabled.isReady)
        assertFalse(LightNfcAvailability.Unsupported.isReady)
        assertFalse(LightNfcAvailability.PermissionMissing.isReady)
    }

    @Test
    fun disabledAvailabilityAsksTheUserToTurnNfcOn() {
        assertEquals(DISABLED_MESSAGE, LightNfcAvailability.Disabled.unavailableMessage())
    }

    @Test
    fun missingHardwareSaysThePhoneCannotUseNfc() {
        assertEquals(UNSUPPORTED_MESSAGE, LightNfcAvailability.Unsupported.unavailableMessage())
    }

    @Test
    fun missingPermissionSaysTheToolLacksAccess() {
        assertEquals(PERMISSION_MISSING_MESSAGE, LightNfcAvailability.PermissionMissing.unavailableMessage())
    }

    @Test
    fun allTechnologiesCoversEveryEntry() {
        assertEquals(LightNfcTechnology.entries.toSet(), LightNfcTechnology.All)
    }

    @Test
    fun defaultConfigPollsEveryTechnologyAndKeepsPlatformSounds() {
        val flags = LightNfcReaderConfig().toReaderFlags()

        assertEquals(
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V,
            flags,
        )
    }

    @Test
    fun readerFlagsFollowSelectedTechnologies() {
        val flags = LightNfcReaderConfig(
            technologies = setOf(LightNfcTechnology.NfcA, LightNfcTechnology.NfcV),
        ).toReaderFlags()

        assertEquals(
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_V,
            flags,
        )
    }

    @Test
    fun readerFlagsCarrySkipNdefCheckAndSilencedSounds() {
        val flags = LightNfcReaderConfig(
            technologies = setOf(LightNfcTechnology.NfcA),
            skipNdefCheck = true,
            platformSounds = false,
        ).toReaderFlags()

        assertEquals(
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            flags,
        )
    }

    @Test
    fun rejectsAnEmptyTechnologySet() {
        assertFailsWith<IllegalArgumentException> {
            LightNfcReaderConfig(technologies = emptySet())
        }
    }

    @Test
    fun defaultConfigCarriesNoReaderExtras() {
        assertNull(LightNfcReaderConfig().toReaderExtras())
    }

    @Test
    fun tapReadsTheFirstUriAndTextRecords() {
        val tap = LightNfcTap(
            serialNumber = "04A2B30F",
            records = listOf(
                LightNfcRecord.Binary(mimeType = "application/octet-stream", bytes = byteArrayOf(0x01)),
                LightNfcRecord.Uri("solana:11111111111111111111111111111111"),
                LightNfcRecord.Text(value = "11111111111111111111111111111111", languageTag = "en"),
                LightNfcRecord.Uri("https://example.com"),
            ),
        )

        assertEquals("solana:11111111111111111111111111111111", tap.uri)
        assertEquals("11111111111111111111111111111111", tap.text)
    }

    @Test
    fun tapWithoutNdefRecordsStillCarriesItsSerialNumber() {
        val tap = LightNfcTap(serialNumber = "04A2B30F", records = emptyList())

        assertEquals("04A2B30F", tap.serialNumber)
        assertNull(tap.uri)
        assertNull(tap.text)
    }
}
