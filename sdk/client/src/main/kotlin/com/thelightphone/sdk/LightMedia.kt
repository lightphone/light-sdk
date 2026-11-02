package com.thelightphone.sdk

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.widget.VideoView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LightMedia internal constructor(private val androidContext: Context) {
    enum class Kind {
        Image,
        Video,
    }

    data class Item(
        val id: Long,
        val name: String,
        val sizeBytes: Long,
        val kind: Kind,
    )

    data class Library(
        val items: List<Item>,
        val truncated: Boolean,
    )

    fun loadImages(limit: Int = MAX_LISTED): Library =
        load(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Kind.Image, "image", limit)

    fun loadVideos(limit: Int = MAX_LISTED): Library =
        load(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Kind.Video, "video", limit)

    suspend fun decodeImage(item: Item): ImageBitmap? {
        if (item.kind != Kind.Image) return null
        return withContext(Dispatchers.IO) { decodeImageBytes(item) }
    }

    /**
     * plays video [item] inside [modifier]. [scale] controls how the picture fills that
     * frame, the same way [ContentScale] does for an [androidx.compose.foundation.Image].
     */
    @Composable
    fun Video(
        item: Item,
        modifier: Modifier = Modifier,
        scale: ContentScale = ContentScale.Fit,
    ) {
        val uri = contentUri(item)
        var videoSize by remember(item) { mutableStateOf<IntSize?>(null) }
        BoxWithConstraints(modifier.clipToBounds()) {
            AndroidView(
                modifier = videoFrame(videoSize, scale),
                factory = { context ->
                    VideoView(context).apply {
                        setVideoURI(uri)
                        setOnPreparedListener { player ->
                            if (player.videoWidth > 0 && player.videoHeight > 0) {
                                videoSize = IntSize(player.videoWidth, player.videoHeight)
                            }
                            start()
                        }
                    }
                },
                onRelease = { it.stopPlayback() },
            )
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.BoxWithConstraintsScope.videoFrame(
        videoSize: IntSize?,
        scale: ContentScale,
    ): Modifier {
        val size = videoSize
        if (size == null || !constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
            return Modifier.fillMaxSize()
        }
        val factor = scale.computeScaleFactor(
            srcSize = Size(size.width.toFloat(), size.height.toFloat()),
            dstSize = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()),
        )
        val width = with(LocalDensity.current) { (size.width * factor.scaleX).toDp() }
        val height = with(LocalDensity.current) { (size.height * factor.scaleY).toDp() }
        return Modifier
            .size(width, height)
            .align(Alignment.Center)
    }

    private fun load(collection: Uri, kind: Kind, singular: String, limit: Int): Library {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
        )
        val items = mutableListOf<Item>()
        var truncated = false

        androidContext.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) {
                if (items.size == limit) {
                    truncated = true
                    break
                }
                val id = cursor.getLong(idColumn)
                items += Item(
                    id = id,
                    name = cursor.getString(nameColumn) ?: "$singular $id",
                    sizeBytes = cursor.getLong(sizeColumn),
                    kind = kind,
                )
            }
        } ?: return Library(emptyList(), truncated = false)

        return Library(items, truncated)
    }

    private fun contentUri(item: Item): Uri {
        val collection = when (item.kind) {
            Kind.Image -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            Kind.Video -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        return ContentUris.withAppendedId(collection, item.id)
    }

    private fun decodeImageBytes(item: Item): ImageBitmap? {
        val uri = contentUri(item)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        androidContext.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_EDGE)
        }
        val bitmap = androidContext.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return null
        return bitmap.asImageBitmap()
    }

    private companion object {
        const val MAX_LISTED = 100
        const val MAX_IMAGE_EDGE = 1080

        fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
            var sample = 1
            while (width / sample > maxEdge || height / sample > maxEdge) {
                sample *= 2
            }
            return sample
        }
    }
}
