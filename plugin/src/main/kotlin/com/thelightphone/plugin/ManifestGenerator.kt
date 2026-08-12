package com.thelightphone.plugin

/**
 * Renders an `AndroidManifest.xml` from validated [LightToolMetadata].
 *
 * The skeleton mirrors what every Light SDK tool needs (LightSdkApplication +
 * LightActivity + LightSdkReceiver + SDK-marker query). The only variation
 * across tools is the label and the set of `<uses-permission>` entries.
 *
 * All user-controlled strings have already been validated, but we XML-escape
 * them anyway so a future loosening of the validators can't open a manifest
 * injection.
 */
object ManifestGenerator {
    fun render(metadata: LightToolMetadata): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine("""<manifest xmlns:android="http://schemas.android.com/apk/res/android">""")
        // A capability declares what the tool does and the permissions it needs
        // follow from that, so they are unioned in here rather than written by the tool.
        val permissions = (
            metadata.permissions + metadata.capabilities.flatMap {
                LightToolPolicy.CAPABILITY_IMPLIED_PERMISSIONS[it].orEmpty()
            }
        ).distinct()
        for (perm in permissions) {
            appendLine("""    <uses-permission android:name="${xmlAttr(perm)}" />""")
        }
        // Emit Play-Store-inferred hardware features as required="false" so
        // PermissionImpliesUnsupportedChromeOsHardware lint stays quiet and
        // we don't accidentally narrow the install pool. Deduped because
        // distinct permissions can map to overlapping feature sets.
        val features = permissions
            .flatMap { LightToolPolicy.PERMISSION_IMPLIED_FEATURES[it].orEmpty() }
            .toSet()
        for (feature in features) {
            appendLine("""    <uses-feature android:name="${xmlAttr(feature)}" android:required="false" />""")
        }
        val screenOrientation = metadata.orientation?.let {
            "\n            |            android:screenOrientation=\"${xmlAttr(it)}\""
        }.orEmpty()
        val capabilityMarkers = marginBlock(
            metadata.capabilities.flatMap { capability ->
                listOf(
                    """        <meta-data""",
                    """            android:name="${xmlAttr(LightToolPolicy.capabilityMarker(capability))}"""",
                    """            android:value="true" />""",
                )
            }
        )
        // Only tools that opted in declare the audio service. Shipping it in the
        // SDK library manifest would put a mediaPlayback claim in every tool.
        val detachedAudioService = marginBlock(
            if (LightToolPolicy.DETACHED_AUDIO !in metadata.capabilities) emptyList() else listOf(
                """        <service""",
                """            android:name="com.thelightphone.sdk.audio.LightAudioService"""",
                """            android:foregroundServiceType="mediaPlayback"""",
                """            android:exported="false">""",
                """            <intent-filter>""",
                """                <action android:name="androidx.media3.session.MediaSessionService" />""",
                """            </intent-filter>""",
                """        </service>""",
            )
        )
        appendLine(
            """
            |    <application
            |        android:name="com.thelightphone.sdk.LightSdkApplication"
            |        android:label="${xmlAttr(metadata.label)}"
            |        android:supportsRtl="true"
            |        android:theme="@style/LightSdk.Theme.Splash">
            |        <meta-data
            |            android:name="com.thelightphone.sdk.LIGHT_SERVER_PACKAGE"
            |            android:value="${xmlAttr(metadata.serverPackage)}" />$capabilityMarkers
            |        <activity
            |            android:name="com.thelightphone.sdk.LightActivity"
            |            android:launchMode="singleTask"$screenOrientation
            |            android:exported="true">
            |            <intent-filter>
            |                <action android:name="android.intent.action.MAIN" />
            |                <category android:name="android.intent.category.LAUNCHER" />
            |            </intent-filter>
            |        </activity>
            |        <receiver
            |            android:name="com.thelightphone.sdk.LightSdkReceiver"
            |            android:enabled="true"
            |            android:exported="true"
            |            android:permission="normal">
            |            <intent-filter>
            |                <action android:name="com.thelightphone.sdk.ACTION_SDK_MARKER" />
            |            </intent-filter>
            |            <meta-data
            |                android:name="com.thelightphone.sdk.SDK_VERSION"
            |                android:value="${'$'}{sdkVersion}" />
            |        </receiver>$detachedAudioService
            |    </application>
            |    <queries>
            |        <intent>
            |            <action android:name="com.thelightphone.sdk.ACTION_SDK_MARKER" />
            |        </intent>
            |    </queries>
            |</manifest>""".trimMargin()
        )
    }

    /**
     * Splices [lines] into the trimMargin template below. Interpolation happens
     * before trimMargin runs, so every inserted line carries its own margin.
     */
    private fun marginBlock(lines: List<String>): String =
        lines.joinToString("") { "\n            |$it" }

    private fun xmlAttr(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }
}
