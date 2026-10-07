package de.freal.threeseconds.montage

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import de.freal.threeseconds.data.Clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

sealed interface MontageResult {
    data class Success(val uri: Uri, val clipCount: Int, val durationMs: Long, val skipped: Int) : MontageResult
    data class Failure(val reason: String) : MontageResult
}

/**
 * Stitches a month of clips into one file.
 *
 * Every clip was written by the same pipeline at the same resolution, so the montage
 * is a straight sample copy -- no decode, no re-encode, no quality loss, and fast
 * enough to run on demand. Clips whose format does not match the first one are
 * skipped rather than silently corrupting the output.
 */
class MontageBuilder(private val context: Context) {

    suspend fun build(
        clips: List<Clip>,
        output: File,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): MontageResult = withContext(Dispatchers.IO) {
        if (clips.isEmpty()) return@withContext MontageResult.Failure("No clips in that month yet")

        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var baseFormat: MediaFormat? = null
        var timeOffsetUs = 0L
        var written = 0
        var skipped = 0

        val buffer = ByteBuffer.allocate(MAX_SAMPLE_BYTES)
        val info = MediaCodec.BufferInfo()

        try {
            for ((index, clip) in clips.withIndex()) {
                coroutineContext.ensureActive()

                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, Uri.parse(clip.uri), null)
                    val track = extractor.firstVideoTrack()
                    if (track < 0) {
                        skipped++
                        continue
                    }
                    extractor.selectTrack(track)
                    val format = extractor.getTrackFormat(track)

                    if (baseFormat == null) {
                        baseFormat = format
                        val m = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        trackIndex = m.addTrack(format)
                        m.start()
                        muxer = m
                    } else if (!format.matches(baseFormat)) {
                        Log.w(TAG, "Skipping ${clip.uri}: format differs from the montage track")
                        skipped++
                        continue
                    }

                    val m = muxer ?: return@withContext MontageResult.Failure("Could not open the montage file")
                    var lastPtsUs = 0L

                    while (true) {
                        coroutineContext.ensureActive()
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break

                        val pts = extractor.sampleTime
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = timeOffsetUs + pts
                        info.flags = extractor.sampleFlags.toMuxerFlags()

                        m.writeSampleData(trackIndex, buffer, info)
                        lastPtsUs = maxOf(lastPtsUs, pts)
                        extractor.advance()
                    }

                    // Leave one frame of spacing so the next clip does not share a timestamp.
                    timeOffsetUs += lastPtsUs + FRAME_GAP_US
                    written++
                } finally {
                    extractor.release()
                }

                onProgress(index + 1, clips.size)
            }

            if (written == 0 || muxer == null) {
                return@withContext MontageResult.Failure("None of those clips could be read")
            }

            MontageResult.Success(
                uri = Uri.fromFile(output),
                clipCount = written,
                durationMs = timeOffsetUs / 1000,
                skipped = skipped,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Montage failed", e)
            MontageResult.Failure(e.message ?: "Could not build the montage")
        } finally {
            muxer?.let { m ->
                runCatching { m.stop() }
                runCatching { m.release() }
            }
        }
    }

    private fun MediaExtractor.firstVideoTrack(): Int {
        for (i in 0 until trackCount) {
            val mime = getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return -1
    }

    /** Same codec and geometry is all the muxer needs to accept the samples. */
    private fun MediaFormat.matches(other: MediaFormat): Boolean =
        getString(MediaFormat.KEY_MIME) == other.getString(MediaFormat.KEY_MIME) &&
            getInteger(MediaFormat.KEY_WIDTH) == other.getInteger(MediaFormat.KEY_WIDTH) &&
            getInteger(MediaFormat.KEY_HEIGHT) == other.getInteger(MediaFormat.KEY_HEIGHT)

    private fun Int.toMuxerFlags(): Int =
        if (this and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

    private companion object {
        const val TAG = "MontageBuilder"
        const val MAX_SAMPLE_BYTES = 1 shl 21
        const val FRAME_GAP_US = 33_333L
    }
}
