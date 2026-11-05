package com.thelightphone.sdk

import java.net.URLDecoder
import java.net.URLEncoder

internal data class LightAlertRecord(
    val id: String,
    val title: String,
    val text: String,
    val triggerAtMillis: Long,
)

internal fun encodeAlerts(records: List<LightAlertRecord>): String =
    records.joinToString("\n") { record ->
        listOf(
            record.id,
            record.title,
            record.text,
            record.triggerAtMillis.toString(),
        ).joinToString("\t") { URLEncoder.encode(it, Charsets.UTF_8) }
    }

internal fun decodeAlerts(raw: String): List<LightAlertRecord> {
    if (raw.isEmpty()) return emptyList()
    return raw.lineSequence().mapNotNull(::decodeAlert).toList()
}

private fun decodeAlert(line: String): LightAlertRecord? {
    val parts = line.split('\t')
    if (parts.size != 4) return null
    val decoded = parts.map { URLDecoder.decode(it, Charsets.UTF_8) }
    val triggerAtMillis = decoded[3].toLongOrNull() ?: return null
    return LightAlertRecord(
        id = decoded[0],
        title = decoded[1],
        text = decoded[2],
        triggerAtMillis = triggerAtMillis,
    )
}

internal const val IMMEDIATE_NOTIFICATION_ID = 1
