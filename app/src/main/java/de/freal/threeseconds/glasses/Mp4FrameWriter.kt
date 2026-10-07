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
 * parameter sets for the track format and then a straight copy of every access
 * unit. That keeps a 3 second capture effectively instant and avoids a second
 * generation of lossy compression on top of the Bluetooth bandwidth ladder.
 */
class Mp4FrameWriter(
    private val outputFile: File,
    private val frameRate: Int,
) : Closeable {

    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var started = false
    private var closed = false

    private var codecConfig: ByteArray? = null
    private var firstPtsUs = -1L
    private var lastPtsUs = 0L

    var frameCount = 0
        private set
    var width = 0
        private set
    var height = 0
        private set

    /** Duration actually captured, derived from frame timestamps. */
    val durationMs: Long
        get() = if (firstPtsUs < 0) 0 else (lastPtsUs - firstPtsUs) / 1000

    /**
     * Returns true once the writer has accepted at least one real frame, i.e. the
     * file will be playable if we stop now.
     */
    val hasVideo: Boolean get() = started && frameCount > 0

    fun onFrame(frame: VideoFrame) {
        if (closed) return
        require(frame.isCompressed) {
            "Mp4FrameWriter needs compressed frames; configure the stream with compressVideo = true"
        }

        val buffer = frame.buffer.duplicate()
        if (buffer.remaining() <= 0) return

        if (frame.isCodecConfig) {
            // The config frame is not necessarily clean Annex-B: observed buffers carry
            // a few leading bytes before the first start code, and MediaMuxer rejects
            // the track if those reach csd-0. Rebuild it from the parsed NAL units.
            codecConfig = HevcNal.parameterSets(buffer)
            if (codecConfig == null) Log.w(TAG, "Codec config frame carried no parameter sets")
            return
        }

        if (!started) {
            // Some firmware inlines the parameter sets ahead of the first IDR rather
            // than sending a standalone codec-config frame, so fall back to scraping
            // them off the front of this access unit.
            val csd = codecConfig ?: HevcNal.parameterSets(buffer)
            if (csd == null) {
                Log.w(TAG, "Dropping frame: no HEVC parameter sets yet")
                return
            }
            start(frame, csd)
        }

        val pts = frame.presentationTimeUs
        if (firstPtsUs < 0) firstPtsUs = pts
        lastPtsUs = maxOf(lastPtsUs, pts)

        val info = MediaCodec.BufferInfo().apply {
            offset = 0
            size = buffer.remaining()
            presentationTimeUs = pts - firstPtsUs
            flags = if (HevcNal.isKeyFrame(buffer)) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        }

        try {
            muxer?.writeSampleData(trackIndex, buffer, info)
            frameCount++
        } catch (e: IllegalStateException) {
            Log.e(TAG, "writeSampleData failed", e)
        }
    }

    private fun start(frame: VideoFrame, csd: ByteArray) {
        width = frame.width
        height = frame.height

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
        }

        val m = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        trackIndex = m.addTrack(format)
        m.start()
        muxer = m
        started = true
        Log.i(TAG, "Muxer started ${width}x$height @ ${frameRate}fps")
    }

    override fun close() {
        if (closed) return
        closed = true
        val m = muxer ?: return
        try {
            if (started && frameCount > 0) m.stop()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Muxer stop failed", e)
        } finally {
            runCatching { m.release() }
            muxer = null
        }
    }

    private companion object {
        const val TAG = "Mp4FrameWriter"
    }
}

/** Minimal Annex-B HEVC NAL parsing: just enough for keyframe and parameter-set detection. */
internal object HevcNal {

    private const val NAL_VPS = 32
    private const val NAL_SPS = 33
    private const val NAL_PPS = 34

    /** IRAP picture range (BLA_W_LP .. RSV_IRAP_VCL23). */
    private val IRAP = 16..23

    private data class Nal(val type: Int, val start: Int, val end: Int)

    fun isKeyFrame(buffer: ByteBuffer): Boolean =
        parse(buffer.toByteArray()).any { it.type in IRAP }

    /**
     * Builds a csd-0 blob holding one VPS, one SPS and one PPS, each re-prefixed with a
     * clean 4 byte start code.
     *
     * Rebuilding rather than slicing matters: the buffers coming off the glasses may
     * carry leading bytes before the first start code, repeated start codes, or the
     * parameter sets inlined ahead of the first IDR. Normalising here means the muxer
     * always sees well-formed csd regardless of which shape arrived.
     */
    fun parameterSets(buffer: ByteBuffer): ByteArray? {
        val bytes = buffer.toByteArray()
        val wanted = listOf(NAL_VPS, NAL_SPS, NAL_PPS)
        val found = parse(bytes).filter { it.type in wanted }
        if (found.isEmpty()) return null

        val out = ByteArrayOutputStream()
        for (type in wanted) {
            val nal = found.firstOrNull { it.type == type } ?: continue
            val payloadStart = nal.start + startCodeLength(bytes, nal.start)
            if (payloadStart >= nal.end) continue
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(bytes, payloadStart, nal.end - payloadStart)
        }
        val csd = out.toByteArray()
        return csd.takeIf { it.isNotEmpty() }
    }

    /** Splits an Annex-B buffer into its NAL units, start code included in each range. */
    private fun parse(bytes: ByteArray): List<Nal> {
        val out = mutableListOf<Nal>()
        var sc = nextStartCode(bytes, 0)
        while (sc >= 0) {
            val payload = sc + startCodeLength(bytes, sc)
            if (payload >= bytes.size) break
            val type = (bytes[payload].toInt() shr 1) and 0x3F
            val next = nextStartCode(bytes, payload)
            out += Nal(type, sc, if (next < 0) bytes.size else next)
            sc = next
        }
        return out
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

    private fun ByteBuffer.toByteArray(): ByteArray {
        val dup = duplicate()
        return ByteArray(dup.remaining()).also { dup.get(it) }
    }
}
