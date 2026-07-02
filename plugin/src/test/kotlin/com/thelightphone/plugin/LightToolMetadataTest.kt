package com.thelightphone.plugin

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals

class LightToolMetadataTest {

    private fun writeToml(dir: Path, body: String): File {
        val file = dir.resolve("lighttool.toml").toFile()
        file.writeText(body)
        return file
    }

    @Test
    fun `happy path parses all fields`(@TempDir dir: Path) {
        val file = writeToml(dir, """
            [tool]
            id = "com.example.mytool"
            label = "My Tool"
            versionCode = 7
            versionName = "1.2.0"
            permissions = ["android.permission.INTERNET"]
            serverPackage = "com.lightos"
        """.trimIndent())

        val meta = LightToolMetadata.parse(file)

        assertEquals("com.example.mytool", meta.toolId)
        assertEquals("My Tool", meta.label)
        assertEquals(7, meta.versionCode)
        assertEquals("1.2.0", meta.versionName)
        assertEquals(listOf("android.permission.INTERNET"), meta.permissions)
        assertEquals("com.lightos", meta.serverPackage)
    }

    @Test
    fun `missing file fails`(@TempDir dir: Path) {
        val file = dir.resolve("lighttool.toml").toFile()
        val ex = assertThrows<LightToolMetadataException> { LightToolMetadata.parse(file) }
        assert(ex.message!!.contains("missing"))
    }

    @Test
    fun `missing tool table fails`(@TempDir dir: Path) {
        val file = writeToml(dir, "# nothing here\n")
        val ex = assertThrows<LightToolMetadataException> { LightToolMetadata.parse(file) }
        assert(ex.message!!.contains("[tool]"))
    }

    @Test
    fun `invalid tool id with capitals fails`(@TempDir dir: Path) {
        val file = writeToml(dir, """
            [tool]
            id = "Com.Example.MyTool"
            label = "X"
            versionCode = 1
            versionName = "1.0"
        """.trimIndent())
        val ex = assertThrows<LightToolMetadataException> { LightToolMetadata.parse(file) }
        assert(ex.message!!.contains("tool.id"))
    }

    @Test
    fun `single-segment tool id fails`(@TempDir dir: Path) {
        val file = writeToml(dir, """
            [tool]
            id = "mytool"
            label = "X"
            versionCode = 1
            versionName = "1.0"
        """.trimIndent())
        val ex = assertThrows<LightToolMetadataException> { LightToolMetadata.parse(file) }
        assert(ex.message!!.contains("tool.id"))
    }

    @Test
    fun `unlisted permission fails`(@TempDir dir: Path) {
        val file = writeToml(dir, """
            [tool]
            id = "com.example.mytool"
            label = "X"
            versionCode = 1
            versionName = "1.0"
            permissions = ["android.permission.READ_SMS"]
        """.trimIndent())
        val ex = assertThrows<LightToolMetadataException> { LightToolMetadata.parse(file) }
        assert(ex.message!!.contains("not allowed"))
    }

    @Test
    fun `versionCode at zero fails`(@TempDir dir: Path) {
        val file = writeToml(dir, """
            [tool]
            id = "com.example.mytool"
            label = "X"
            versionCode = 0
            versionName = "1.0"
        """.trimIndent())
        val ex = assertThrows<LightToolMetadataException> { LightToolMetadata.parse(file) }
        assert(ex.message!!.contains("versionCode"))
    }

    @Test
    fun `label with angle bracket fails`(@TempDir dir: Path) {
        val file = writeToml(dir, """
            [tool]
            id = "com.example.mytool"
            label = "<script>"
            versionCode = 1
            versionName = "1.0"
        """.trimIndent())
        val ex = assertThrows<LightToolMetadataException> { LightToolMetadata.parse(file) }
        assert(ex.message!!.contains("label"))
    }

    @Test
    fun `duplicate permission fails`(@TempDir dir: Path) {
        val file = writeToml(dir, """
            [tool]
            id = "com.example.mytool"
            label = "X"
            versionCode = 1
            versionName = "1.0"
            permissions = [
                "android.permission.INTERNET",
                "android.permission.INTERNET",
            ]
        """.trimIndent())
        val ex = assertThrows<LightToolMetadataException> { LightToolMetadata.parse(file) }
        assert(ex.message!!.contains("duplicate"))
    }

    @Test
    fun `wrong type for versionCode fails with clear message`(@TempDir dir: Path) {
        val file = writeToml(dir, """
            [tool]
            id = "com.example.mytool"
            label = "X"
            versionCode = "1"
            versionName = "1.0"
        """.trimIndent())
        val ex = assertThrows<LightToolMetadataException> { LightToolMetadata.parse(file) }
        assert(ex.message!!.contains("versionCode")) { ex.message ?: "" }
    }

    @Test
    fun `missing serverPackage fails`(@TempDir dir: Path) {
        val file = writeToml(dir, """
            [tool]
            id = "com.example.mytool"
            label = "X"
            versionCode = 1
            versionName = "1.0"
        """.trimIndent())
        val ex = assertThrows<LightToolMetadataException> { LightToolMetadata.parse(file) }
        assert(ex.message!!.contains("serverPackage"))
    }

    @Test
    fun `invalid serverPackage with capitals fails`(@TempDir dir: Path) {
        val file = writeToml(dir, """
            [tool]
            id = "com.example.mytool"
            label = "X"
            versionCode = 1
            versionName = "1.0"
            serverPackage = "Com.LightOS"
        """.trimIndent())
        val ex = assertThrows<LightToolMetadataException> { LightToolMetadata.parse(file) }
        assert(ex.message!!.contains("serverPackage"))
    }

    @Test
    fun `single-segment serverPackage fails`(@TempDir dir: Path) {
        val file = writeToml(dir, """
            [tool]
            id = "com.example.mytool"
            label = "X"
            versionCode = 1
            versionName = "1.0"
            serverPackage = "lightos"
        """.trimIndent())
        val ex = assertThrows<LightToolMetadataException> { LightToolMetadata.parse(file) }
        assert(ex.message!!.contains("serverPackage"))
    }
}
