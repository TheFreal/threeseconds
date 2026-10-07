package de.freal.threeseconds.glasses

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.meta.wearable.dat.camera.types.VideoFrame
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer

/**
 * Muxes the compressed HEVC frames the glasses stream into an .mp4.
 *
 * The DAT SDK hands us already-encoded HEVC when the stream is configured with
 * `compressVideo = true`, so there is nothing to encode here -- we only need the
 * parameter sets for the track format and then a copy of every access unit. That keeps
 * a 3 second capture effectively instant and avoids a second generation of lossy
 * compression on top of the Bluetooth bandwidth ladder.
 *
 * Capture and muxing are split. [onFrame] only copies the bytes out of the SDK's buffer,
 * because on real glasses that buffer is reused for the next frame: any time spent
 * parsing or muxing inside the collector let the next frame overwrite the current one
 * before we had read it. Three seconds of HEVC is well under a megabyte, so the frames
 * are held in memory and written out in one go by [finish].
 */
class Mp4FrameWriter(
    private val outputFile: File,
    private val frameRate: Int,
) : Closeable {

    private class Captured(val bytes: ByteArray, val ptsUs: Long, val isConfig: Boolean)

    private val captured = ArrayList<Captured>(128)
    private var finished = false

    private var firstPtsUs = -1L
    private var lastPtsUs = 0L

    var frameCount = 0
        private set
    var droppedCount = 0
        private set
    var width = 0
        private set
    var height = 0
        private set

    /** Duration actually captured, derived from frame timestamps. */
    val durationMs: Long
        get() = if (firstPtsUs < 0) 0 else (lastPtsUs - firstPtsUs) / 1000

    /** Returns true once [finish] has written at least one playable frame. */
    val hasVideo: Boolean get() = frameCount > 0

    fun onFrame(frame: VideoFrame) {
        if (finished) return
        require(frame.isCompressed) {
            "Mp4FrameWriter needs compressed frames; configure the stream with compressVideo = true"
        }

        // Copy first, before anything else touches the frame.
        val src = frame.buffer.duplicate()
        if (src.remaining() <= 0) return
        val bytes = ByteArray(src.remaining()).also { src.get(it) }

        if (frame.isCodecConfig) {
            captured += Captured(bytes, 0, isConfig = true)
            return
        }

        if (width == 0) {
            width = frame.width
            height = frame.height
        }
        val pts = frame.presentationTimeUs
        if (firstPtsUs < 0) firstPtsUs = pts
        lastPtsUs = maxOf(lastPtsUs, pts)
        captured += Captured(bytes, pts, isConfig = false)
    }

    /**
     * Cleans up the captured access units and writes the .mp4. Returns true if the file
     * holds at least one frame. Safe to call more than once.
     */
    fun finish(): Boolean {
        if (finished) return hasVideo
        finished = true

        var codecConfig: ByteArray? = null
        val samples = ArrayList<Sample>(captured.size)
        for (c in captured) {
            if (c.isConfig) {
                // Config frames are not necessarily clean Annex-B either, so rebuild
                // csd-0 from the parsed NAL units rather than passing the buffer on.
                codecConfig = HevcNal.parameterSets(c.bytes) ?: codecConfig
                if (codecConfig == null) Log.w(TAG, "Codec config frame carried no parameter sets")
                continue
            }
            samples += Sample(c.bytes, c.ptsUs)
        }
        captured.clear()

        val kept = HevcNal.dropOverwritten(samples)
        droppedCount = samples.size - kept.size
        if (droppedCount > 0) Log.w(TAG, "Dropped $droppedCount frames overwritten before they were read")

        var muxer: MediaMuxer? = null
        var track = -1
        try {
            for (sample in kept) {
                val au = HevcNal.normalize(sample.bytes) ?: continue
                val key = HevcNal.isKeyFrame(au)

                if (muxer == null) {
                    // Nothing before the first IRAP picture can be decoded, and some
                    // firmware inlines the parameter sets ahead of the first IDR rather
                    // than sending a standalone codec-config frame.
                    if (!key) continue
                    val csd = codecConfig ?: HevcNal.parameterSets(au)
                    if (csd == null) {
                        Log.w(TAG, "Dropping keyframe: no HEVC parameter sets yet")
                        continue
                    }
                    muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    track = muxer.addTrack(trackFormat(csd))
                    muxer.start()
                    firstPtsUs = sample.ptsUs
                    Log.i(TAG, "Muxer started ${width}x$height @ ${frameRate}fps")
                }

                val info = MediaCodec.BufferInfo().apply {
                    offset = 0
                    size = au.size
                    presentationTimeUs = sample.ptsUs - firstPtsUs
                    flags = if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                }
                muxer.writeSampleData(track, ByteBuffer.wrap(au), info)
                frameCount++
                lastPtsUs = sample.ptsUs
            }
            if (muxer != null && frameCount > 0) muxer.stop()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Muxing failed", e)
            frameCount = 0
        } finally {
            runCatching { muxer?.release() }
        }
        return hasVideo
    }

    private fun trackFormat(csd: ByteArray): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
        }

    override fun close() {
        finished = true
        captured.clear()
    }

    private companion object {
        const val TAG = "Mp4FrameWriter"
    }
}

internal class Sample(val bytes: ByteArray, val ptsUs: Long)

/**
 * Minimal Annex-B HEVC NAL parsing: enough to clean up what the glasses send and to
 * find keyframes and parameter sets.
 *
 * Real glasses put a small transport header in front of every access unit (it contains
 * an RTP header: `80 60`, sequence number, timestamp), followed by zero padding, before
 * the first start code. MediaMuxer treats everything between start codes as a NAL unit,
 * so passing those buffers through unchanged wrote the header into every sample as a
 * bogus NAL unit and no decoder could play the file.
 */
internal object HevcNal {

    private const val NAL_VPS = 32
    private const val NAL_SPS = 33
    private const val NAL_PPS = 34

    /** IRAP picture range (BLA_W_LP .. RSV_IRAP_VCL23). */
    private val IRAP = 16..23

    /** NAL unit types defined by the spec; reserved and unspecified ones are dropped. */
    private val DEFINED = (0..9) + (16..21) + (32..40)

    private class Nal(val type: Int, val payloadStart: Int, val end: Int)

    fun isKeyFrame(bytes: ByteArray): Boolean = parse(bytes).any { it.type in IRAP }

    /**
     * Rebuilds an access unit as clean Annex-B: anything before the first start code
     * is discarded, empty and malformed NAL units are dropped, and every remaining unit
     * gets a 4 byte start code. Returns null if nothing usable is left.
     */
    fun normalize(bytes: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(bytes.size + 16)
        for (nal in parse(bytes)) {
            if (nal.type !in DEFINED) continue
            out.write(START_CODE)
            out.write(bytes, nal.payloadStart, nal.end - nal.payloadStart)
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    /**
     * Builds a csd-0 blob holding one VPS, one SPS and one PPS, each prefixed with a
     * 4 byte start code.
     */
    fun parameterSets(bytes: ByteArray): ByteArray? {
        val wanted = listOf(NAL_VPS, NAL_SPS, NAL_PPS)
        val found = parse(bytes).filter { it.type in wanted }
        if (found.isEmpty()) return null

        val out = ByteArrayOutputStream()
        for (type in wanted) {
            val nal = found.firstOrNull { it.type == type } ?: continue
            out.write(START_CODE)
            out.write(bytes, nal.payloadStart, nal.end - nal.payloadStart)
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    /**
     * Drops frames whose buffer was overwritten by the next frame before we read it.
     *
     * The glasses reuse one buffer per frame, and a frame read too late carries the next
     * frame's transport header and picture with its own (stale) length. That shows up as
     * two consecutive frames with identical headers; the earlier copy is the torn one.
     * Frames without a transport header (e.g. the mock device) are never dropped.
     */
    fun dropOverwritten(samples: List<Sample>): List<Sample> {
        val out = ArrayList<Sample>(samples.size)
        var previousHeader: ByteArray? = null
        for (sample in samples) {
            val header = transportHeader(sample.bytes)
            if (header != null && previousHeader != null && header.contentEquals(previousHeader)) {
                out.removeAt(out.lastIndex)
            }
            out += sample
            previousHeader = header
        }
        return out
    }

    /** The bytes ahead of the first start code, or null if the buffer starts with one. */
    private fun transportHeader(bytes: ByteArray): ByteArray? {
        val sc = nextStartCode(bytes, 0)
        if (sc <= 0) return null
        return bytes.copyOfRange(0, sc)
    }

    /**
     * Splits an Annex-B buffer into its NAL units. Leading bytes before the first start
     * code are skipped, trailing zero padding is trimmed, and units too short to hold a
     * header or with an invalid header are dropped.
     */
    private fun parse(bytes: ByteArray): List<Nal> {
        val out = mutableListOf<Nal>()
        var sc = nextStartCode(bytes, 0)
        while (sc >= 0) {
            val payload = sc + startCodeLength(bytes, sc)
            if (payload >= bytes.size) break
            val next = nextStartCode(bytes, payload)
            var end = if (next < 0) bytes.size else next
            while (end > payload && bytes[end - 1].toInt() == 0) end--
            if (end - payload >= 2 && validHeader(bytes[payload].toInt(), bytes[payload + 1].toInt())) {
                out += Nal((bytes[payload].toInt() shr 1) and 0x3F, payload, end)
            }
            sc = next
        }
        return out
    }

    /** forbidden_zero_bit clear, base layer, temporal id present. */
    private fun validHeader(b0: Int, b1: Int): Boolean {
        val forbidden = b0 and 0x80
        val layerId = ((b0 and 0x01) shl 5) or ((b1 and 0xF8) shr 3)
        val temporalIdPlus1 = b1 and 0x07
        return forbidden == 0 && layerId == 0 && temporalIdPlus1 != 0
    }

    private fun startCodeLength(bytes: ByteArray, at: Int): Int =
        if (at + 3 < bytes.size && bytes[at + 2].toInt() == 0x00) 4 else 3

    private fun nextStartCode(bytes: ByteArray, from: Int): Int {
        var i = maxOf(0, from)
        while (i + 2 < bytes.size) {
            if (bytes[i].toInt() == 0x00 && bytes[i + 1].toInt() == 0x00) {
                if (bytes[i + 2].toInt() == 0x01) return i
                if (i + 3 < bytes.size && bytes[i + 2].toInt() == 0x00 && bytes[i + 3].toInt() == 0x01) return i
            }
            i++
        }
        return -1
    }

    private val START_CODE = byteArrayOf(0, 0, 0, 1)
}
