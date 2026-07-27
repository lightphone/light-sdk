package com.thelightphone.sdk

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore

/**
 * A music track from the device's shared audio library (MediaStore). [uri] is a
 * content URI the caller can hand to a player via
 * [LightAudioLibrary.openFileDescriptor].
 */
class LightAudioTrack(
    val uri: Uri,
    val title: String,
    val artist: String?,
    val album: String?,
    val genre: String?,
    val trackNumber: Int,
    val durationMs: Long,
)

/**
 * Read access to the device's shared audio library via MediaStore. Obtained from
 * [SealedLightContext.audioLibrary]. Requires the tool to hold
 * `android.permission.READ_MEDIA_AUDIO`; without it [queryTracks] returns an
 * empty list rather than throwing.
 *
 * Holds only the application context, so it is safe to retain beyond a screen.
 */
class LightAudioLibrary internal constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    /** All music tracks on the device, in MediaStore's default order. */
    fun queryTracks(): List<LightAudioTrack> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.GENRE,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        val tracks = ArrayList<LightAudioTrack>()
        appContext.contentResolver.query(collection, projection, selection, null, null)
            ?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val genreCol = cursor.getColumnIndex(MediaStore.Audio.Media.GENRE)
                val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    // MediaStore encodes TRACK as disc*1000 + track; keep the track part.
                    val rawTrack = if (cursor.isNull(trackCol)) 0 else cursor.getInt(trackCol)
                    tracks.add(
                        LightAudioTrack(
                            uri = ContentUris.withAppendedId(collection, id),
                            title = cursor.getString(titleCol) ?: "",
                            artist = cursor.getString(artistCol),
                            album = cursor.getString(albumCol),
                            genre = if (genreCol >= 0) cursor.getString(genreCol) else null,
                            trackNumber = if (rawTrack >= 1000) rawTrack % 1000 else rawTrack,
                            durationMs = if (cursor.isNull(durationCol)) 0L else cursor.getLong(durationCol),
                        ),
                    )
                }
            }
        return tracks
    }

    /** Opens a read-only descriptor for a [queryTracks] URI, or null if it can't be opened. */
    fun openFileDescriptor(uri: Uri): ParcelFileDescriptor? =
        runCatching { appContext.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
}

/** Read access to the device's shared audio library (MediaStore). */
val SealedLightContext.audioLibrary: LightAudioLibrary
    get() = LightAudioLibrary(androidContext)

/**
 * Routes the hardware volume keys to the media stream and shows the in-app volume
 * HUD (LightOS's own volume UI does not render over tool windows). Call from a
 * media tool, e.g. in a screen's `willShow`; it persists for the tool's Activity.
 */
fun SealedLightContext.useMediaVolumeKeys() {
    val context = androidContext
    if (context is LightActivity) {
        context.enableMediaVolumeKeys()
    }
}
