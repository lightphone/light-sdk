package com.thelightphone.plugin

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManifestGeneratorTest {

    private fun render(
        label: String = "My App",
        permissions: List<String> = emptyList(),
        serverPackage: String = "com.lightos",
    ): String = ManifestGenerator.render(
        LightToolMetadata(
            toolId = "com.example.mytool",
            label = label,
            versionCode = 1,
            versionName = "1.0",
            permissions = permissions,
            serverPackage = serverPackage,
        )
    )

    @Test
    fun `empty permissions produces no uses-permission elements`() {
        val xml = render(permissions = emptyList())
        assertFalse(xml.contains("uses-permission"))
    }

    @Test
    fun `each permission becomes one uses-permission element`() {
        val xml = render(permissions = listOf(
            "android.permission.INTERNET",
            "android.permission.CAMERA",
        ))
        assertTrue(xml.contains("""<uses-permission android:name="android.permission.INTERNET" />"""))
        assertTrue(xml.contains("""<uses-permission android:name="android.permission.CAMERA" />"""))
    }

    @Test
    fun `label is xml-escaped`() {
        // The validator would never accept this label, but the generator
        // must still escape it as a second layer of defense.
        val xml = render(label = "Bobby & \"Tables\"")
        assertTrue(xml.contains("Bobby &amp; &quot;Tables&quot;"))
    }

    @Test
    fun `manifest carries the sdkVersion placeholder`() {
        val xml = render()
        // AGP substitutes ${sdkVersion} from manifestPlaceholders. The
        // generated manifest must keep that token intact.
        assertTrue(xml.contains("\${sdkVersion}"))
    }

    @Test
    fun `camera permission emits a matching uses-feature`() {
        // Without this, lint trips PermissionImpliesUnsupportedChromeOsHardware.
        val xml = render(permissions = listOf("android.permission.CAMERA"))
        assertTrue(
            xml.contains("""<uses-feature android:name="android.hardware.camera" android:required="false" />"""),
            "expected android.hardware.camera uses-feature; got:\n$xml"
        )
    }

    @Test
    fun `record_audio permission emits microphone uses-feature`() {
        val xml = render(permissions = listOf("android.permission.RECORD_AUDIO"))
        assertTrue(xml.contains("""<uses-feature android:name="android.hardware.microphone" android:required="false" />"""))
    }

    @Test
    fun `permission without implied feature emits no uses-feature`() {
        val xml = render(permissions = listOf("android.permission.INTERNET"))
        assertFalse(xml.contains("uses-feature"))
    }

    @Test
    fun `server package is emitted as meta-data in application element`() {
        val xml = render(serverPackage = "com.lightos")
        assertTrue(
            xml.contains("""android:name="com.thelightphone.sdk.LIGHT_SERVER_PACKAGE""""),
            "expected LIGHT_SERVER_PACKAGE meta-data name; got:\n$xml"
        )
        assertTrue(
            xml.contains("""android:value="com.lightos""""),
            "expected com.lightos as meta-data value; got:\n$xml"
        )
    }
}
