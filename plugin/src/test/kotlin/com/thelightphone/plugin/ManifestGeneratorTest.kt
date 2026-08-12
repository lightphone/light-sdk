package com.thelightphone.plugin

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManifestGeneratorTest {

    private fun render(
        label: String = "My App",
        permissions: List<String> = emptyList(),
        capabilities: List<String> = emptyList(),
        serverPackage: String = "com.lightos",
        orientation: String? = null,
    ): String = ManifestGenerator.render(
        LightToolMetadata(
            toolId = "com.example.mytool",
            label = label,
            versionCode = 1,
            versionName = "1.0.0",
            permissions = permissions,
            capabilities = capabilities,
            serverPackage = serverPackage,
            orientation = orientation,
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
    fun `orientation is omitted by default`() {
        assertFalse(render().contains("screenOrientation"))
    }

    @Test
    fun `portrait orientation is emitted on activity`() {
        assertTrue(render(orientation = "portrait").contains("""android:screenOrientation="portrait"""))
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
    fun `nfc permission emits nfc uses-feature`() {
        val xml = render(permissions = listOf("android.permission.NFC"))
        assertTrue(xml.contains("""<uses-permission android:name="android.permission.NFC" />"""))
        assertTrue(
            xml.contains("""<uses-feature android:name="android.hardware.nfc" android:required="false" />"""),
            "expected android.hardware.nfc uses-feature; got:\n$xml"
        )
    }

    @Test
    fun `permission without implied feature emits no uses-feature`() {
        val xml = render(permissions = listOf("android.permission.INTERNET"))
        assertFalse(xml.contains("uses-feature"))
    }

    @Test
    fun `detached-audio capability generates its foreground service permissions`() {
        val xml = render(capabilities = listOf("detached-audio"))

        assertTrue(xml.contains("""<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />"""))
        assertTrue(xml.contains("""<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />"""))
        // Neither permission implies hardware, so nothing narrows the install pool.
        assertFalse(xml.contains("uses-feature"))
    }

    @Test
    fun `detached-audio capability emits its marker meta-data`() {
        val xml = render(capabilities = listOf("detached-audio"))

        assertTrue(
            xml.contains("""android:name="com.thelightphone.sdk.CAPABILITY_DETACHED_AUDIO""""),
            "expected the capability marker; got:\n$xml"
        )
    }

    @Test
    fun `detached-audio capability declares the audio service`() {
        val xml = render(capabilities = listOf("detached-audio"))

        assertTrue(
            xml.contains("""android:name="com.thelightphone.sdk.audio.LightAudioService""""),
            "expected LightAudioService; got:\n$xml"
        )
        assertTrue(xml.contains("""android:foregroundServiceType="mediaPlayback""""))
        assertTrue(xml.contains("""<action android:name="androidx.media3.session.MediaSessionService" />"""))
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

    @Test
    fun `without the capability nothing detached-audio is emitted`() {
        // A tool that never asked for detached playback must carry no service,
        // no mediaPlayback claim, no marker, and no foreground permissions.
        val xml = render(permissions = listOf("android.permission.RECORD_AUDIO"))

        assertFalse(xml.contains("<service"))
        assertFalse(xml.contains("android.permission.FOREGROUND_SERVICE"))
        assertFalse(xml.contains("foregroundServiceType"))
        assertFalse(xml.contains("CAPABILITY_DETACHED_AUDIO"))
    }
}
