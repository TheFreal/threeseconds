package de.freal.threeseconds

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Encodes a synthetic HEVC clip on device, so the mock camera has something realistic
 * to play back without shipping a binary fixture or depending on ffmpeg.
 */
object HevcFixture {

    fun create(output: File, width: Int = 504, height: Int = 896, frameRate: Int = 24, seconds: Int = 6): File {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false

        val totalFrames = frameRate * seconds
        val frameDurationUs = 1_000_000L / frameRate
        val info = MediaCodec.BufferInfo()

        var inputFrame = 0
        var sawOutputEos = false

        while (!sawOutputEos) {
            if (inputFrame <= totalFrames) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)!!
                    buf.clear()
                    if (inputFrame == totalFrames) {
                        codec.queueInputBuffer(
                            inIndex, 0, 0, inputFrame * frameDurationUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                    } else {
                        val yuv = yuv420(width, height, inputFrame)
                        buf.put(yuv)
                        codec.queueInputBuffer(inIndex, 0, yuv.size, inputFrame * frameDurationUs, 0)
                    }
                    inputFrame++
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    val out = codec.getOutputBuffer(outIndex)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && muxerStarted) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, out, info)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                }
            }
        }

        codec.stop()
        codec.release()
        muxer.stop()
        muxer.release()
        return output
    }

    /** A moving gradient, so successive frames genuinely differ and compress realistically. */
    private fun yuv420(width: Int, height: Int, frame: Int): ByteArray {
        val ySize = width * height
        val data = ByteArray(ySize * 3 / 2)
        for (y in 0 until height) {
            for (x in 0 until width) {
                data[y * width + x] = (((x + frame * 7) xor (y + frame * 3)) and 0xFF).toByte()
            }
        }
        var i = ySize
        while (i < data.size) {
            data[i] = (128 + (frame % 64) - 32).toByte()
            i++
        }
        return data
    }
}
