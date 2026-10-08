package de.freal.threeseconds.glasses

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File

/**
 * Encodes the spooled frames into an .mp4 once the camera is off.
 *
 * Capture and encoding are deliberately separate: the camera only has to stay on for the
 * seconds being recorded, and with only a few seconds of video there is no reason to be
 * frugal. Every frame is encoded at a generous bitrate with a keyframe each second, so a
 * frame the glasses dropped shows up as a short skip rather than corrupting the frames
 * after it. HEVC keeps the clips compatible with the monthly montage; AVC is the fallback
 * on a phone without an HEVC encoder.
 */
internal class ClipEncoder(private val spool: FrameSpool) {

    class Result(
        val width: Int,
        val height: Int,
        val frameCount: Int,
        val durationMs: Long,
        /** Source and encoder details for the debug screen. */
        val summary: String,
    )

    fun encode(output: File): Result? {
        val entries = spool.entries
        if (entries.isEmpty()) return null

        // The bandwidth ladder can change resolution mid-clip; the clip keeps the size most
        // frames arrived at and scales the rest onto it.
        val (canvasW, canvasH) = entries.groupingBy { it.width to it.height }.eachCount().maxBy { it.value }.key
        val sample = entries.filter { it.width == canvasW && it.height == canvasH }.let { it[it.size / 2] }
        val frame = ByteArray(spool.largestFrame)
        spool.read(sample, frame)
        val (stride, slice) = YuvLayout.geometry(sample.size, canvasW, canvasH)
            ?: return null.also { Log.e(TAG, "Frame of ${sample.size} bytes is too small for ${canvasW}x$canvasH") }
        val semiPlanar = YuvLayout.looksSemiPlanar(frame, canvasW, canvasH, stride, slice)
        val layout = YuvLayout(stride, slice, semiPlanar)

        val encoder = pickEncoder(canvasW, canvasH)
            ?: return null.also { Log.e(TAG, "No encoder takes ${canvasW}x$canvasH") }
        val fps = measuredFps(entries)
        val bitrate = encoder.bitrate(fps)

        val format = MediaFormat.createVideoFormat(encoder.mime, encoder.width, encoder.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.toInt().coerceAtLeast(1))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Parameter sets in every keyframe make each clip self-contained, which keeps
            // a montage of clips from different encoder sessions decodable.
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            if (encoder.vbr) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
        }

        val codec = MediaCodec.createByCodecName(encoder.name)
        var muxer: MediaMuxer? = null
        var track = -1
        var written = 0
        var skipped = 0
        var lastPtsUs = 0L
        val info = MediaCodec.BufferInfo()

        fun drain(untilEos: Boolean) {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos) return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
                            track = it.addTrack(codec.outputFormat)
                            it.start()
                        }
                    }
                    index >= 0 -> {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val m = muxer
                        if (!config && info.size > 0 && m != null) {
                            m.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                            written++
                            lastPtsUs = maxOf(lastPtsUs, info.presentationTimeUs)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (eos) return
                    }
                }
            }
        }

        try {
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: IllegalArgumentException) {
                // Not every encoder can repeat the parameter sets; the track header still
                // carries them, which is all a single clip needs.
                Log.w(TAG, "${encoder.name} rejected repeated headers; configuring without", e)
                format.removeKey(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES)
                codec.reset()
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            codec.start()

            val firstPts = entries.first().ptsUs
            var previousPts = -1L
            for (entry in entries) {
                val pts = entry.ptsUs - firstPts
                if (pts <= previousPts) {
                    skipped++ // the muxer needs strictly rising timestamps
                    continue
                }
                val geometry = if (entry.width == canvasW && entry.height == canvasH) {
                    stride to slice
                } else {
                    YuvLayout.geometry(entry.size, entry.width, entry.height) ?: run {
                        skipped++
                        null
                    } ?: continue
                }
                spool.read(entry, frame)

                var index: Int
                do {
                    index = codec.dequeueInputBuffer(10_000)
                    if (index < 0) drain(untilEos = false)
                } while (index < 0)

                // The capacity has to be read before the image: fetching the buffer after
                // would invalidate the image.
                val capacity = codec.getInputBuffer(index)!!.capacity()
                val image = codec.getInputImage(index)!!
                copyIntoImage(
                    frame, entry.width, entry.height,
                    YuvLayout(geometry.first, geometry.second, semiPlanar),
                    canvasW, canvasH, image,
                )
                codec.queueInputBuffer(index, 0, capacity, pts, 0)
                previousPts = pts
                drain(untilEos = false)
            }

            var index: Int
            do {
                index = codec.dequeueInputBuffer(10_000)
                if (index < 0) drain(untilEos = false)
            } while (index < 0)
            codec.queueInputBuffer(index, 0, 0, previousPts + frameUs(fps), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(untilEos = true)

            muxer?.stop()
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { muxer?.release() }
        }

        if (written == 0) return null
        val mbps = "%.1f".format(bitrate / 1_000_000f)
        val codecLabel = if (encoder.mime == MediaFormat.MIMETYPE_VIDEO_HEVC) "HEVC" else "AVC"
        return Result(
            width = encoder.width,
            height = encoder.height,
            frameCount = written,
            durationMs = (lastPtsUs + frameUs(fps)) / 1000,
            summary = "${canvasW}x$canvasH $layout → ${encoder.width}x${encoder.height} $codecLabel " +
                "$mbps Mbps (${encoder.name}), $written frames out" +
                if (skipped > 0) ", $skipped skipped" else "",
        )
    }

    private class Encoder(
        val name: String,
        val mime: String,
        val width: Int,
        val height: Int,
        val vbr: Boolean,
        private val maxBitrate: Int,
    ) {
        /** About 0.4 bits per pixel: several times what the glasses send, so effectively lossless here. */
        fun bitrate(fps: Float): Int =
            (width.toLong() * height * fps * 0.4f).toLong().coerceIn(1_000_000L, maxBitrate.toLong()).toInt()
    }

    /**
     * The first encoder that takes the clip's size, preferring HEVC and hardware. Sizes an
     * encoder can't take (504 is not a multiple of 16) are cropped by a few pixels rather
     * than scaled.
     */
    private fun pickEncoder(width: Int, height: Int): Encoder? {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
        for (mime in listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)) {
            val candidates = all.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
                .sortedByDescending { it.isHardwareAccelerated }
            for (info in candidates) {
                val caps = info.getCapabilitiesForType(mime)
                val video = caps.videoCapabilities ?: continue
                val w = width - width % video.widthAlignment
                val h = height - height % video.heightAlignment
                if (w <= 0 || h <= 0 || !video.isSizeSupported(w, h)) {
                    Log.i(TAG, "${info.name} can't take ${w}x$h (widths ${video.supportedWidths}, heights ${video.supportedHeights})")
                    continue
                }
                return Encoder(
                    name = info.name,
                    mime = mime,
                    width = w,
                    height = h,
                    vbr = caps.encoderCapabilities
                        ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true,
                    maxBitrate = video.bitrateRange.upper,
                )
            }
        }
        return null
    }

    private fun measuredFps(entries: List<FrameSpool.Entry>): Float {
        if (entries.size < 2) return 24f
        val spanUs = entries.last().ptsUs - entries.first().ptsUs
        return if (spanUs > 0) (entries.size - 1) * 1_000_000f / spanUs else 24f
    }

    private fun frameUs(fps: Float): Long = (1_000_000f / fps.coerceAtLeast(1f)).toLong()

    private companion object {
        const val TAG = "ClipEncoder"
    }
}
