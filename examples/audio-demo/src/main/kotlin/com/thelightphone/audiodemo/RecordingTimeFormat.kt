package com.thelightphone.audiodemo

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

object RecordingTimeFormat {
    private val zone: ZoneId
        get() = ZoneId.systemDefault()

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val dayFormatter = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.US)
    private val yearFormatter = DateTimeFormatter.ofPattern("MMM d yyyy HH:mm", Locale.US)

    fun relativeLabel(createdAt: Long): String {
        val created = Instant.ofEpochMilli(createdAt).atZone(zone).toLocalDateTime()
        val today = LocalDate.now(zone)
        return when (created.toLocalDate()) {
            today -> "Today ${created.format(timeFormatter)}"
            today.minusDays(1) -> "Yesterday ${created.format(timeFormatter)}"
            else -> created.format(if (created.year == today.year) dayFormatter else yearFormatter)
        }
    }

    fun duration(ms: Long): String {
        val totalSeconds = max(0L, ms) / 1000L
        val seconds = totalSeconds % 60L
        val minutes = (totalSeconds / 60L) % 60L
        val hours = totalSeconds / 3600L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
        } else {
            "%d:%02d".format(Locale.US, minutes, seconds)
        }
    }
}
