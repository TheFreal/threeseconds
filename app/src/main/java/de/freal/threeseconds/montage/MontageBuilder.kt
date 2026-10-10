package de.freal.threeseconds.montage

import android.content.Context
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import de.freal.threeseconds.data.Clip
import de.freal.threeseconds.glasses.YuvLayout
import de.freal.threeseconds.glasses.copyIntoImage
import de.freal.threeseconds.glasses.pickEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

sealed interface MontageResult {
    data class Success(val uri: Uri, val clipCount: Int, val durationMs: Long, val skipped: Int) : MontageResult
    data class Failure(val reason: String) : MontageResult
}

/**
 * Stitches a month of clips into one file.
 *
 * Every clip is decoded and re-encoded into a single track. Copying samples straight
 * across was faster, but only worked while every clip shared codec, size and encoder
 * settings; one old or odd clip made all the others "mismatched". Now the montage takes
 * the size most clips have, scales the rest onto it, and a clip that can't be read or
 * decoded is skipped on its own instead of taking the others with it.
 */
class MontageBuilder(private val context: Context) {

    private class Probe(val clip: Clip, val width: Int, val height: Int)

    suspend fun build(
        clips: List<Clip>,
        output: File,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): MontageResult = withContext(Dispatchers.Default) {
        if (clips.isEmpty()) return@withContext MontageResult.Failure("No clips in that month yet")

        val probes = clips.mapNotNull { probe(it) }
        var skipped = clips.size - probes.size
        if (probes.isEmpty()) return@withContext MontageResult.Failure("None of those clips could be read")

        val (canvasW, canvasH) = probes.groupingBy { it.width to it.height }.eachCount().maxBy { it.value }.key
        val encoder = pickEncoder(canvasW, canvasH)
            ?: return@withContext MontageResult.Failure("This phone has no encoder for ${canvasW}x$canvasH")

        val codec = MediaCodec.createByCodecName(encoder.name)
        val sink = Sink(codec, output)
        var offsetUs = 0L
        var written = 0

        try {
            codec.configure(encoder.format(FPS), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            for ((index, probe) in probes.withIndex()) {
                coroutineContext.ensureActive()
                val end = try {
                    reencode(probe, codec, sink, offsetUs, canvasW, canvasH)
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping ${probe.clip.uri}: ${e.message}", e)
                    null
                }
                if (end == null) {
                    skipped++
                } else {
                    written++
                    offsetUs = end + FRAME_GAP_US
                }
                onProgress(index + 1, probes.size)
            }

            val eos = sink.dequeueInput()
            codec.queueInputBuffer(eos, 0, 0, offsetUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            sink.drain(untilEos = true)
            sink.finish()

            if (written == 0 || sink.samples == 0) {
                return@withContext MontageResult.Failure("None of those clips could be decoded")
            }
            MontageResult.Success(Uri.fromFile(output), written, offsetUs / 1000, skipped)
        } catch (e: Exception) {
            Log.e(TAG, "Montage failed", e)
            MontageResult.Failure(e.message ?: "Could not build the montage")
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            sink.release()
        }
    }

    /** The clip's video size, or null if it has no readable video track. */
    private fun probe(clip: Clip): Probe? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(clip.uri), null)
            val track = extractor.firstVideoTrack()
            if (track < 0) return@runCatching null
            val format = extractor.getTrackFormat(track)
            Probe(clip, format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT))
        } finally {
            extractor.release()
        }
    }.onFailure { Log.w(TAG, "Can't read ${clip.uri}", it) }.getOrNull()

    /**
     * Decodes one clip into the encoder, its timestamps shifted to start at [offsetUs].
     * Returns the last timestamp written, or null if no frame came out.
     */
    private suspend fun reencode(
        probe: Probe,
        encoder: MediaCodec,
        sink: Sink,
        offsetUs: Long,
        canvasW: Int,
        canvasH: Int,
    ): Long? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(probe.clip.uri), null)
            val track = extractor.firstVideoTrack()
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val dec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder = dec
            dec.configure(format, null, null, 0)
            dec.start()

            val info = MediaCodec.BufferInfo()
            var frame = ByteArray(0)
            var firstPts = -1L
            var lastPts: Long? = null
            var inputDone = false
            while (true) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val i = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val n = extractor.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val o = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o < 0) continue
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val image = if (info.size > 0) dec.getOutputImage(o) else null
                if (image != null) {
                    val crop = image.cropRect
                    val w = crop.width() and 1.inv()
                    val h = crop.height() and 1.inv()
                    if (frame.size < w * h * 3 / 2) frame = ByteArray(w * h * 3 / 2)
                    image.toI420(crop, w, h, frame)

                    if (firstPts < 0) firstPts = info.presentationTimeUs
                    val pts = offsetUs + info.presentationTimeUs - firstPts
                    // The muxer needs strictly rising timestamps across the whole montage.
                    if (lastPts == null || pts > lastPts) {
                        val index = sink.dequeueInput()
                        val capacity = encoder.getInputBuffer(index)!!.capacity()
                        copyIntoImage(frame, w, h, YuvLayout(w, h, false), canvasW, canvasH, encoder.getInputImage(index)!!)
                        encoder.queueInputBuffer(index, 0, capacity, pts, 0)
                        lastPts = pts
                        sink.drain(untilEos = false)
                    }
                }
                dec.releaseOutputBuffer(o, false)
                if (eos) return lastPts
            }
        } finally {
            decoder?.let { d ->
                runCatching { d.stop() }
                runCatching { d.release() }
            }
            extractor.release()
        }
    }

    /** Encoder output into the montage file; the muxer starts once the encoder's format is known. */
    private class Sink(private val codec: MediaCodec, private val output: File) {
        private var muxer: MediaMuxer? = null
        private var track = -1
        private val info = MediaCodec.BufferInfo()
        var samples = 0
            private set

        fun dequeueInput(): Int {
            while (true) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) return index
                drain(untilEos = false)
            }
        }

        fun drain(untilEos: Boolean) {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, if (untilEos) TIMEOUT_US else 0)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos) return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
                            track = it.addTrack(codec.outputFormat)
                            it.start()
                        }
                    }
                    index >= 0 -> {
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val m = muxer
                        if (!config && info.size > 0 && m != null) {
                            m.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                            samples++
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        fun finish() {
            muxer?.stop()
        }

        fun release() {
            runCatching { muxer?.release() }
            muxer = null
        }
    }

    private fun MediaExtractor.firstVideoTrack(): Int {
        for (i in 0 until trackCount) {
            val mime = getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return -1
    }

    private companion object {
        const val TAG = "MontageBuilder"
        const val TIMEOUT_US = 10_000L
        const val FPS = 24f
        const val FRAME_GAP_US = 41_667L
    }
}

/** Copies the visible part of a decoded 4:2:0 image into [out] as tight I420 of [w]x[h]. */
private fun Image.toI420(crop: Rect, w: Int, h: Int, out: ByteArray) {
    var at = 0
    for ((i, plane) in planes.withIndex()) {
        val sub = if (i == 0) 1 else 2
        val buf = plane.buffer
        val pw = w / sub
        val ph = h / sub
        val left = crop.left / sub
        val top = crop.top / sub
        for (y in 0 until ph) {
            val rowStart = (y + top) * plane.rowStride + left * plane.pixelStride
            if (plane.pixelStride == 1) {
                buf.position(rowStart)
                buf.get(out, at, pw)
                at += pw
            } else {
                for (x in 0 until pw) out[at++] = buf.get(rowStart + x * plane.pixelStride)
            }
        }
    }
}
