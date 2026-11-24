package com.thelightphone.sdk.emulator.http

import com.thelightphone.sdk.trust.SignedTrustBundle
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun parseTrustBundleUpload(request: String): SignedTrustBundle {
    val body = Json.parseToJsonElement(request) as? JsonObject
        ?: throw IllegalArgumentException("Expected a JSON object")
    val bytes = body.requireString("bytesBase64")
    val signature = body.requireString("signatureBase64")
    val decoder = Base64.getDecoder()
    return SignedTrustBundle(decoder.decode(bytes), decoder.decode(signature))
}

private fun JsonObject.requireString(name: String): String {
    val value = this[name] as? JsonPrimitive
    require(value != null && value.isString) { "Expected a string for " + name }
    return value.content
}
