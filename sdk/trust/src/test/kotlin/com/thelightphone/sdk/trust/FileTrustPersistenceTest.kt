package com.thelightphone.sdk.trust

import java.nio.file.Files
import kotlin.test.*

class FileTrustPersistenceTest {
    @Test
    fun `missing file reads as no bundle`() {
        val path = Files.createTempDirectory("trust-persistence-test").resolve("bundle.dat")
        assertNull(FileTrustPersistence(path).read())
    }

    @Test
    fun `round trips bytes and detached signature exactly`() {
        val path = Files.createTempDirectory("trust-persistence-test").resolve("bundle.dat")
        val persistence = FileTrustPersistence(path)
        val bundle =
            SignedTrustBundle(ByteArray(5000) { it.toByte() }, ByteArray(64) { (it * 3).toByte() })

        persistence.write(bundle)
        val read = FileTrustPersistence(path).read()

        assertNotNull(read)
        assertContentEquals(bundle.bytes, read.bytes)
        assertContentEquals(bundle.signature, read.signature)
    }

    @Test
    fun `a later write replaces the earlier bundle`() {
        val path = Files.createTempDirectory("trust-persistence-test").resolve("bundle.dat")
        val persistence = FileTrustPersistence(path)
        persistence.write(SignedTrustBundle(byteArrayOf(1), byteArrayOf(1)))
        persistence.write(SignedTrustBundle(byteArrayOf(2, 2), byteArrayOf(2, 2)))

        val read = persistence.read()

        assertNotNull(read)
        assertContentEquals(byteArrayOf(2, 2), read.bytes)
    }

    @Test
    fun `oversized signature is rejected before replacing the previous record`() {
        val path = Files.createTempFile("trust-signature-size", ".dat")
        try {
            val persistence = FileTrustPersistence(path)
            val signature = ByteArray(100_000) { it.toByte() }
            persistence.write(SignedTrustBundle(byteArrayOf(1), signature))
            assertContentEquals(signature, assertNotNull(persistence.read()).signature)

            assertFailsWith<IllegalArgumentException> {
                persistence.write(SignedTrustBundle(byteArrayOf(2), ByteArray(100_001)))
            }

            val saved = assertNotNull(FileTrustPersistence(path).read())
            assertContentEquals(byteArrayOf(1), saved.bytes)
            assertContentEquals(signature, saved.signature)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `tampered file is rejected rather than silently truncated`() {
        val path = Files.createTempDirectory("trust-persistence-test").resolve("bundle.dat")
        val persistence = FileTrustPersistence(path)
        persistence.write(SignedTrustBundle(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6)))

        val bytes = Files.readAllBytes(path)
        bytes[bytes.size - 1] = bytes[bytes.size - 1].inc()
        Files.write(path, bytes)

        assertFailsWith<IllegalArgumentException> { persistence.read() }
    }
}
