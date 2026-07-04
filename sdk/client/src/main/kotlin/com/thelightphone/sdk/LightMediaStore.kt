package com.thelightphone.sdk

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.InputStream

class LightMediaStore internal constructor(private val androidContext: Context) {

    enum class MediaType(internal val mediaStoreValue: Int) {
        IMAGE(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
        VIDEO(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO),
    }

    data class MediaItem(
        val uri: Uri,
        val displayName: String,
        val size: Long,
        val relativePath: String,
        val dateTakenMillis: Long?,
        val mimeType: String,
        val type: MediaType,
    )

    fun list(types: Set<MediaType> = setOf(MediaType.IMAGE, MediaType.VIDEO)): List<MediaItem> {
        require(types.isNotEmpty()) { "types must not be empty" }

        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )

        val typePlaceholders = types.joinToString(",") { "?" }
        val selection =
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN ($typePlaceholders) AND " +
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = types.map { it.mediaStoreValue.toString() }.toTypedArray() + "DCIM/%"
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_TAKEN} DESC"

        val results = mutableListOf<MediaItem>()

        androidContext.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val typeValue = cursor.getInt(typeCol)
                val mediaType = MediaType.entries.firstOrNull { it.mediaStoreValue == typeValue }
                    ?: continue
                results += MediaItem(
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = cursor.getString(nameCol).orEmpty(),
                    size = cursor.getLong(sizeCol),
                    relativePath = cursor.getString(pathCol).orEmpty(),
                    dateTakenMillis = if (cursor.isNull(dateCol)) null else cursor.getLong(dateCol),
                    mimeType = cursor.getString(mimeCol).orEmpty(),
                    type = mediaType,
                )
            }
        }

        return results
    }

    fun listImages(): List<MediaItem> = list(setOf(MediaType.IMAGE))

    fun listVideos(): List<MediaItem> = list(setOf(MediaType.VIDEO))

    fun <T> read(item: MediaItem, block: (InputStream) -> T): T? {
        return androidContext.contentResolver.openInputStream(item.uri)?.use(block)
    }
}
