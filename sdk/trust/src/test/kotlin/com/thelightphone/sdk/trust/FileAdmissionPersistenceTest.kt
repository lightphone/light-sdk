package com.thelightphone.sdk.trust

import java.nio.file.Files
import kotlin.test.*

class FileAdmissionPersistenceTest {
    @Test
    fun `a second instance over the same path is refused`() {
        val directory = Files.createTempDirectory("admission-owner-test")
        val path = directory.resolve("records")
        try {
            FileAdmissionPersistence(path)
            assertFailsWith<IllegalArgumentException> { FileAdmissionPersistence(path) }
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `different literal paths to the same location are still recognized as one owner`() {
        val directory = Files.createTempDirectory("admission-owner-test")
        val path = directory.resolve("records")
        try {
            FileAdmissionPersistence(path)
            // same location, different (non-normalized) literal path string
            val equivalent = directory.resolve(".").resolve("records")
            assertFailsWith<IllegalArgumentException> { FileAdmissionPersistence(equivalent) }
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `distinct paths do not collide`() {
        val directory = Files.createTempDirectory("admission-owner-test")
        val a = directory.resolve("records-a")
        val b = directory.resolve("records-b")
        try {
            FileAdmissionPersistence(a)
            FileAdmissionPersistence(b) // must not throw
        } finally {
            Files.deleteIfExists(a)
            Files.deleteIfExists(b)
            Files.deleteIfExists(directory)
        }
    }
}
