package com.thelightphone.sdk.trust.apksig

import com.android.apksig.ApkSigner
import com.thelightphone.sdk.trust.StampResult
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ApkSigStampVerifierTest {
    @Test fun `missing APK is an infrastructure failure, not a content fact`() {
        assertIs<StampResult.Unavailable>(ApkSigStampVerifier().verify(listOf("missing.apk")))
    }

    @Test fun `split APK verification is platform-only`() {
        assertIs<StampResult.Unavailable>(ApkSigStampVerifier().verify(listOf("base.apk", "split.apk")))
    }

    @Test fun `garbage non-zip content is a content fact, not an infrastructure failure`() {
        val file = Files.createTempFile("garbage-", ".apk").toFile()
        try {
            file.writeBytes(ByteArray(64) { it.toByte() })
            assertIs<StampResult.NotPresent>(ApkSigStampVerifier().verify(listOf(file.path)))
        } finally {
            file.delete()
        }
    }

    @Test fun `signed and stamped APK verifies against the stamp certificate`() {
        val (apk, stampCert) = signedFixture(tamper = false)
        try {
            val result = assertIs<StampResult.Verified>(ApkSigStampVerifier().verify(listOf(apk.path)))
            assertEquals(sha256(stampCert.encoded), result.certSha256)
        } finally {
            apk.delete()
        }
    }

    @Test fun `content edited after signing does not verify despite an intact stamp`() {
        val (apk, _) = signedFixture(tamper = true)
        try {
            assertIs<StampResult.NotVerified>(ApkSigStampVerifier().verify(listOf(apk.path)))
        } finally {
            apk.delete()
        }
    }

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    /**
     * Builds a tiny real-shaped APK (binary-XML manifest + a payload entry), signs it with
     * v1-v3 plus a source stamp using throwaway, in-memory-generated keys, and optionally
     * flips a payload byte after signing (preserving the signing block) to exercise the
     * tamper path. No committed binary fixtures and no system properties: everything is
     * generated at test time. [MINIMAL_MANIFEST] is the one exception - a compiled (AAPT2)
     * binary XML manifest, inlined as base64 because apksig's full verify() hard-requires a
     * parseable `AndroidManifest.xml` entry to determine the checked platform version range,
     * and that format isn't practical to hand-assemble.
     */
    private fun signedFixture(tamper: Boolean): Pair<File, X509Certificate> {
        val unsigned = Files.createTempFile("unsigned-", ".apk").toFile()
        ZipOutputStream(unsigned.outputStream()).use { zip ->
            storedEntry(zip, "AndroidManifest.xml", Base64.getDecoder().decode(MINIMAL_MANIFEST))
            storedEntry(zip, "payload.bin", PAYLOAD)
        }

        val (appKey, appCert) = generateCertificate("app")
        val (stampKey, stampCert) = generateCertificate("stamp")
        val signed = Files.createTempFile("signed-", ".apk").toFile()
        ApkSigner.Builder(listOf(signerConfig("app", appKey, appCert)))
            .setSourceStampSignerConfig(signerConfig("stamp", stampKey, stampCert))
            .setInputApk(unsigned)
            .setOutputApk(signed)
            .setMinSdkVersion(24)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
        unsigned.delete()

        if (tamper) {
            // Flip a byte inside the payload entry's (uncompressed) content, well before the
            // central directory / signing block at the tail of the file, so the zip structure
            // and signature blocks stay intact but the digested bytes change - the same shape
            // as a surgical post-sign edit that leaves the stamp itself untouched.
            val bytes = signed.readBytes()
            val offset = (0..bytes.size - PAYLOAD.size).first { at ->
                PAYLOAD.indices.all { bytes[at + it] == PAYLOAD[it] }
            }
            val tampered = PAYLOAD.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            tampered.copyInto(bytes, offset)
            // STORED entries carry their CRC-32 in both the local and central-directory
            // headers; fix those up too so the edit looks like a signature/content-digest
            // mismatch to apksig, not zip corruption, matching a surgical post-sign edit.
            val oldCrcLe = crc32LittleEndian(PAYLOAD)
            val newCrcLe = crc32LittleEndian(tampered)
            var patched = 0
            var at = 0
            while (at <= bytes.size - oldCrcLe.size) {
                if (oldCrcLe.indices.all { bytes[at + it] == oldCrcLe[it] }) {
                    newCrcLe.copyInto(bytes, at)
                    patched++
                    at += oldCrcLe.size
                } else {
                    at++
                }
            }
            check(patched == 2) { "expected to patch exactly 2 CRC fields (local + central), patched $patched" }
            signed.writeBytes(bytes)
        }

        return signed to stampCert
    }

    private fun crc32LittleEndian(content: ByteArray): ByteArray {
        val crc = CRC32().apply { update(content) }.value
        return byteArrayOf(crc.toByte(), (crc shr 8).toByte(), (crc shr 16).toByte(), (crc shr 24).toByte())
    }

    private fun storedEntry(zip: ZipOutputStream, name: String, content: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = content.size.toLong()
        entry.compressedSize = content.size.toLong()
        entry.crc = CRC32().apply { update(content) }.value
        zip.putNextEntry(entry)
        zip.write(content)
        zip.closeEntry()
    }

    private fun signerConfig(alias: String, key: PrivateKey, cert: X509Certificate) =
        ApkSigner.SignerConfig.Builder(alias, key, listOf(cert), /* deterministicDsaSigning= */ false).build()

    private fun generateCertificate(alias: String): Pair<PrivateKey, X509Certificate> {
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        val store = Files.createTempFile("keystore-$alias-", ".jks").toFile()
        store.delete()
        val password = "changeit"
        val process = ProcessBuilder(
            keytool, "-genkeypair",
            "-keystore", store.path,
            "-storepass", password,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-alias", alias,
            "-dname", "CN=Light Trust Test $alias",
            "-validity", "1",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "keytool failed for $alias: $output" }
        try {
            val keyStore = KeyStore.getInstance("JKS")
            store.inputStream().use { keyStore.load(it, password.toCharArray()) }
            val key = keyStore.getKey(alias, password.toCharArray()) as PrivateKey
            val cert = keyStore.getCertificate(alias) as X509Certificate
            return key to cert
        } finally {
            store.delete()
        }
    }

    companion object {
        private val PAYLOAD = ByteArray(32) { it.toByte() }

        // `aapt2 link` output for a one-line `<uses-sdk android:minSdkVersion="24"
        // android:targetSdkVersion="24"/>` manifest with package "com.example.fixture".
        private const val MINIMAL_MANIFEST =
            "AwAIAIADAAABABwALAIAAA0AAAAAAAAAAAAAAFAAAAAAAAAAAAAAAB4AAABCAAAAaAAAAJ4AAACmAAAAuAAAAOIAAAA6AQAATgEAAGABAACUAQAAyAEAAA0AbQBpAG4AUwBkAGsAVgBlAHIAcwBpAG8AbgAAABAAdABhAHIAZwBlAHQAUwBkAGsAVgBlAHIAcwBpAG8AbgAAABEAYwBvAG0AcABpAGwAZQBTAGQAawBWAGUAcgBzAGkAbwBuAAAAGQBjAG8AbQBwAGkAbABlAFMAZABrAFYAZQByAHMAaQBvAG4AQwBvAGQAZQBuAGEAbQBlAAAAAgAxADQAAAAHAGEAbgBkAHIAbwBpAGQAAAATAGMAbwBtAC4AZQB4AGEAbQBwAGwAZQAuAGYAaQB4AHQAdQByAGUAAAAqAGgAdAB0AHAAOgAvAC8AcwBjAGgAZQBtAGEAcwAuAGEAbgBkAHIAbwBpAGQALgBjAG8AbQAvAGEAcABrAC8AcgBlAHMALwBhAG4AZAByAG8AaQBkAAAACABtAGEAbgBpAGYAZQBzAHQAAAAHAHAAYQBjAGsAYQBnAGUAAAAYAHAAbABhAHQAZgBvAHIAbQBCAHUAaQBsAGQAVgBlAHIAcwBpAG8AbgBDAG8AZABlAAAAGABwAGwAYQB0AGYAbwByAG0AQgB1AGkAbABkAFYAZQByAHMAaQBvAG4ATgBhAG0AZQAAAAgAdQBzAGUAcwAtAHMAZABrAAAAgAEIABgAAAAMAgEBcAIBAXIFAQFzBQEBAAEQABgAAAACAAAA/////wUAAAAHAAAAAgEQAIgAAAACAAAA//////////8IAAAAFAAUAAUAAAAAAAAABwAAAAIAAAD/////CAAAECIAAAAHAAAAAwAAAAQAAAAIAAADBAAAAP////8JAAAABgAAAAgAAAMGAAAA/////woAAAD/////CAAAECIAAAD/////CwAAAP////8IAAAQDgAAAAIBEABMAAAABAAAAP//////////DAAAABQAFAACAAAAAAAAAAcAAAAAAAAA/////wgAABAYAAAABwAAAAEAAAD/////CAAAEBgAAAADARAAGAAAAAQAAAD//////////wwAAAADARAAGAAAAAIAAAD//////////wgAAAABARAAGAAAAAIAAAD/////BQAAAAcAAAA="
    }
}
