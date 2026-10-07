package de.freal.threeseconds.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Writes finished clips into a dedicated folder under Movies so they show up in the
 * gallery and survive an uninstall, rather than hiding in app-private storage.
 */
class ClipStore(private val context: Context) {

    suspend fun save(
        source: File,
        recordedAt: Long,
        width: Int,
        height: Int,
        durationMs: Long,
    ): Uri = withContext(Dispatchers.IO) {
        val stamp = FILE_STAMP.format(Instant.ofEpochMilli(recordedAt).atZone(ZoneId.systemDefault()))
        val name = "3s_$stamp.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Video.Media.DATE_TAKEN, recordedAt)
            put(MediaStore.Video.Media.WIDTH, width)
            put(MediaStore.Video.Media.HEIGHT, height)
            put(MediaStore.Video.Media.DURATION, durationMs)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore refused to create $name")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: error("Could not open $uri for writing")

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        uri
    }

    /** Writes a finished montage alongside the clips. */
    suspend fun saveMontage(source: File, month: String): Uri = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "ThreeSeconds_$month.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "$RELATIVE_PATH/Montages")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore refused to create the montage")

        resolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: error("Could not open $uri for writing")

        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
    }

    suspend fun delete(uri: Uri) = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.delete(uri, null, null) }
        Unit
    }

    companion object {
        const val FOLDER_NAME = "ThreeSeconds"
        val RELATIVE_PATH: String = "${Environment.DIRECTORY_MOVIES}/$FOLDER_NAME"
        private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    }
}
