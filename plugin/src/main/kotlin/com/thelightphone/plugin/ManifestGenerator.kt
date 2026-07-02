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
        for (perm in metadata.permissions) {
            appendLine("""    <uses-permission android:name="${xmlAttr(perm)}" />""")
        }
        // Emit Play-Store-inferred hardware features as required="false" so
        // PermissionImpliesUnsupportedChromeOsHardware lint stays quiet and
        // we don't accidentally narrow the install pool. Deduped because
        // distinct permissions can map to overlapping feature sets.
        val features = metadata.permissions
            .flatMap { LightToolPolicy.PERMISSION_IMPLIED_FEATURES[it].orEmpty() }
            .toSet()
        for (feature in features) {
            appendLine("""    <uses-feature android:name="${xmlAttr(feature)}" android:required="false" />""")
        }
        appendLine("""    <application""")
        appendLine("""        android:name="com.thelightphone.sdk.LightSdkApplication"""")
        appendLine("""        android:label="${xmlAttr(metadata.label)}"""")
        appendLine("""        android:supportsRtl="true"""")
        appendLine("""        android:theme="@style/LightSdk.Theme.Splash">""")
        appendLine("""        <meta-data""")
        appendLine("""            android:name="com.thelightphone.sdk.LIGHT_SERVER_PACKAGE"""")
        appendLine("""            android:value="${xmlAttr(metadata.serverPackage)}" />""")
        appendLine("""        <activity""")
        appendLine("""            android:name="com.thelightphone.sdk.LightActivity"""")
        appendLine("""            android:exported="true">""")
        appendLine("""            <intent-filter>""")
        appendLine("""                <action android:name="android.intent.action.MAIN" />""")
        appendLine("""                <category android:name="android.intent.category.LAUNCHER" />""")
        appendLine("""            </intent-filter>""")
        appendLine("""        </activity>""")
        appendLine("""        <receiver""")
        appendLine("""            android:name="com.thelightphone.sdk.LightSdkReceiver"""")
        appendLine("""            android:enabled="true"""")
        appendLine("""            android:exported="true"""")
        appendLine("""            android:permission="normal">""")
        appendLine("""            <intent-filter>""")
        appendLine("""                <action android:name="com.thelightphone.sdk.ACTION_SDK_MARKER" />""")
        appendLine("""            </intent-filter>""")
        appendLine("""            <meta-data""")
        appendLine("""                android:name="com.thelightphone.sdk.SDK_VERSION"""")
        appendLine("""                android:value="${'$'}{sdkVersion}" />""")
        appendLine("""        </receiver>""")
        appendLine("""    </application>""")
        appendLine("""    <queries>""")
        appendLine("""        <intent>""")
        appendLine("""            <action android:name="com.thelightphone.sdk.ACTION_SDK_MARKER" />""")
        appendLine("""        </intent>""")
        appendLine("""    </queries>""")
        appendLine("""</manifest>""")
    }

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
