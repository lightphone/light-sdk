package com.thelightphone.audiodemo

import android.media.MediaMetadataRetriever
import com.thelightphone.sdk.audio.LightAudioUsage
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class AudioClip(
    val source: AudioClipSource,
    val displayName: String,
    val usage: LightAudioUsage,
    val kind: AudioContentKind,
    val durationMs: Long,
    val formatLabel: String,
)

sealed interface AudioClipSource {
    data class FileSource(val file: File) : AudioClipSource
    data class AssetSource(val assetPath: String) : AudioClipSource
    data class UrlSource(val url: String) : AudioClipSource
}

enum class AudioContentKind {
    Music,
    Speech,
}

object SampleAudioCatalog {
    val clips: List<AudioClip> = listOf(
        AudioClip(
            source = AudioClipSource.AssetSource("audio/seletores-de-frequencia.mp3"),
            displayName = "Seletores de frequencia",
            usage = LightAudioUsage.Music,
            kind = AudioContentKind.Music,
            durationMs = 20_036L,
            formatLabel = "MP3",
        ),
        AudioClip(
            source = AudioClipSource.AssetSource("audio/small-talk-build-iv.ogg"),
            displayName = "Small Talk Build IV",
            usage = LightAudioUsage.Music,
            kind = AudioContentKind.Music,
            durationMs = 20_000L,
            formatLabel = "OGG",
        ),
        AudioClip(
            source = AudioClipSource.AssetSource("audio/audrey-tang-interview.mp3"),
            displayName = "Audrey Tang interview",
            usage = LightAudioUsage.Music,
            kind = AudioContentKind.Speech,
            durationMs = 20_036L,
            formatLabel = "MP3",
        ),
        AudioClip(
            source = AudioClipSource.UrlSource(
                "https://commons.wikimedia.org/wiki/Special:Redirect/file/" +
                    "Wikipedia_-_The_Dawn_of_Everything.mp3",
            ),
            displayName = "The Dawn of Everything",
            usage = LightAudioUsage.Music,
            kind = AudioContentKind.Speech,
            durationMs = 1_148_000L,
            formatLabel = "MP3 stream",
        ),
        AudioClip(
            source = AudioClipSource.UrlSource(
                "https://ice6.somafm.com/groovesalad-128-mp3",
            ),
            displayName = "SomaFM Groove Salad",
            usage = LightAudioUsage.Music,
            kind = AudioContentKind.Music,
            durationMs = 0L,
            formatLabel = "STREAM",
        ),
    )
}

class AudioLibraryRepository internal constructor(
    filesDir: File,
    private val bundledClips: List<AudioClip> = SampleAudioCatalog.clips,
    private val durationResolver: (File) -> Long = ::readDurationMs,
    private val now: () -> Instant = Instant::now,
) {
    private val recordingsDir = File(filesDir, "recordings")
    private val fileNameFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
        .withZone(ZoneId.systemDefault())

    fun list(): List<AudioClip> = bundledClips + recordingsDir
        .listFiles { file -> file.isFile && file.extension.equals("m4a", ignoreCase = true) }
        ?.map { file ->
            AudioClip(
                source = AudioClipSource.FileSource(file),
                displayName = RecordingTimeFormat.relativeLabel(createdAt(file)),
                usage = LightAudioUsage.Music,
                kind = AudioContentKind.Speech,
                durationMs = durationResolver(file),
                formatLabel = "M4A",
            )
        }
        ?.sortedByDescending { (it.source as AudioClipSource.FileSource).file.createdAt() }
        .orEmpty()

    fun newRecordingFile(): File {
        recordingsDir.mkdirs()
        val baseName = fileNameFormatter.format(now())
        var candidate = File(recordingsDir, "$baseName.m4a")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(recordingsDir, "$baseName-$suffix.m4a")
            suffix++
        }
        return candidate
    }

    private fun createdAt(file: File): Long = file.createdAt()
}

private fun File.createdAt(): Long {
    val datePart = nameWithoutExtension.substringBefore("-").takeIf { it.length == 8 }
    val timePart = nameWithoutExtension.substringAfter("-", "").take(6).takeIf { it.length == 6 }
    if (datePart != null && timePart != null) {
        runCatching {
            val local = java.time.LocalDateTime.parse(
                "$datePart$timePart",
                DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US),
            )
            return local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    }
    return lastModified()
}

private fun readDurationMs(file: File): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (_: RuntimeException) {
        0L
    } finally {
        retriever.release()
    }
}
