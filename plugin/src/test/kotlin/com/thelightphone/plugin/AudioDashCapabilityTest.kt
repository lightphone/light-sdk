package com.thelightphone.plugin

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioDashCapabilityTest {

    private fun writeToml(dir: Path, capabilities: String): File {
        val file = dir.resolve("lighttool.toml").toFile()
        file.writeText(
            """
            [tool]
            id = "com.example.mytool"
            label = "My Tool"
            versionCode = 1
            versionName = "1.0.0"
            capabilities = [$capabilities]
            serverPackage = "com.lightos"
            """.trimIndent()
        )
        return file
    }

    @Test
    fun `audio-dash is a declarable capability`(@TempDir dir: Path) {
        val meta = LightToolMetadata.parse(writeToml(dir, "\"audio-dash\""))

        assertEquals(listOf("audio-dash"), meta.capabilities)
    }

    @Test
    fun `audio-dash combines with detached audio`(@TempDir dir: Path) {
        val meta = LightToolMetadata.parse(writeToml(dir, "\"detached-audio\", \"audio-dash\""))

        assertEquals(listOf("detached-audio", "audio-dash"), meta.capabilities)
    }

    @Test
    fun `an unknown audio capability is rejected`(@TempDir dir: Path) {
        val ex = assertThrows<LightToolMetadataException> {
            LightToolMetadata.parse(writeToml(dir, "\"audio-hls\""))
        }

        assertTrue(ex.message!!.contains("capability not allowed: audio-hls"))
    }

    @Test
    fun `audio-dash contributes the media3 dash source`() {
        assertEquals(
            listOf("androidx.media3:media3-exoplayer-dash:${LightToolPolicy.MEDIA3_VERSION}"),
            LightToolPolicy.CAPABILITY_IMPLIED_DEPENDENCIES[LightToolPolicy.AUDIO_DASH],
        )
    }

    /** Keep the plugin's media3 version in sync with the catalog. */
    @Test
    fun `the hardcoded media3 version tracks the version catalog`() {
        val catalog = File(System.getProperty("light.versionCatalog"))
        assertTrue(catalog.isFile, "version catalog not found at ${catalog.path}")

        val declared = Regex("""^media3\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(catalog.readText())
            ?.groupValues
            ?.get(1)

        assertEquals(declared, LightToolPolicy.MEDIA3_VERSION)
    }
}
