package de.freal.threeseconds.glasses

import android.media.Image
import kotlin.math.abs

/**
 * How the SDK's decoder laid out a YUV 4:2:0 frame.
 *
 * The SDK asks its decoder for planar I420 and hands over the raw output buffer with the
 * picture size but without the row stride, and hardware decoders are free to pad rows
 * and planes or to answer with interleaved chroma instead. So the layout is worked out
 * from the buffer itself rather than assumed.
 */
internal data class YuvLayout(val stride: Int, val sliceHeight: Int, val semiPlanar: Boolean) {

    override fun toString(): String =
        (if (semiPlanar) "NV12" else "I420") + if (stride > 0) ", stride $stride/$sliceHeight" else ""

    companion object {
        private val ALIGNMENTS = intArrayOf(1, 2, 16, 32, 64, 128, 256, 512)

        /**
         * The row stride and plane height that make a 4:2:0 frame of [width]x[height]
         * exactly [size] bytes, or the unpadded layout if nothing fits exactly.
         */
        fun geometry(size: Int, width: Int, height: Int): Pair<Int, Int>? {
            for (a in ALIGNMENTS) for (b in ALIGNMENTS) {
                val stride = align(width, a)
                val slice = align(height, b)
                if (stride.toLong() * slice * 3 / 2 == size.toLong()) return stride to slice
            }
            return if (size >= width * height * 3 / 2) width to height else null
        }

        /**
         * Tells interleaved chroma from planar by how alike neighbouring bytes are. In
         * planar chroma, neighbours are adjacent samples of one plane and so nearly equal;
         * interleaved, they alternate U and V, and the closely matching pairs are two apart.
         * In a nearly colourless picture the two read the same, and then the choice barely
         * shows either way.
         */
        fun looksSemiPlanar(frame: ByteArray, width: Int, height: Int, stride: Int, slice: Int): Boolean {
            val chroma = stride * slice
            var lag1 = 0L
            var lag2 = 0L
            val rows = height / 2
            val step = maxOf(1, rows / 32)
            for (row in 0 until rows step step) {
                val start = chroma + row * stride
                val end = minOf(start + width - 2, frame.size - 2)
                var i = start
                while (i < end) {
                    lag1 += abs((frame[i].toInt() and 0xFF) - (frame[i + 1].toInt() and 0xFF))
                    lag2 += abs((frame[i].toInt() and 0xFF) - (frame[i + 2].toInt() and 0xFF))
                    i += 2
                }
            }
            return lag1 > lag2 * 3 / 2 + 1_000
        }

        private fun align(value: Int, to: Int) = (value + to - 1) / to * to
    }
}

/** One plane of a source frame inside a byte array. */
private class SourcePlane(val offset: Int, val rowStride: Int, val pixelStride: Int, val width: Int, val height: Int)

/**
 * Copies a decoded frame into an encoder input [Image], cropping it to the image's size
 * (centred) and, should the glasses' bandwidth ladder have changed resolution mid-clip,
 * scaling it bilinearly onto the clip's main frame size first.
 *
 * @param canvasWidth the clip's main frame size, which the crop is taken from
 */
internal fun copyIntoImage(
    frame: ByteArray,
    width: Int,
    height: Int,
    layout: YuvLayout,
    canvasWidth: Int,
    canvasHeight: Int,
    image: Image,
) {
    val stride = layout.stride
    val slice = layout.sliceHeight
    val chroma = stride * slice
    val planes = if (layout.semiPlanar) {
        listOf(
            SourcePlane(0, stride, 1, width, height),
            SourcePlane(chroma, stride, 2, width / 2, height / 2),
            SourcePlane(chroma + 1, stride, 2, width / 2, height / 2),
        )
    } else {
        val chromaStride = stride / 2
        listOf(
            SourcePlane(0, stride, 1, width, height),
            SourcePlane(chroma, chromaStride, 1, width / 2, height / 2),
            SourcePlane(chroma + chromaStride * (slice / 2), chromaStride, 1, width / 2, height / 2),
        )
    }

    val outW = image.width
    val outH = image.height
    // Even offsets keep the chroma crop aligned with the luma crop.
    val cropX = (canvasWidth - outW) / 2 and 1.inv()
    val cropY = (canvasHeight - outH) / 2 and 1.inv()

    for ((i, src) in planes.withIndex()) {
        val sub = if (i == 0) 1 else 2
        val dst = image.planes[i]
        copyPlane(
            frame, src,
            canvasWidth / sub, canvasHeight / sub, cropX / sub, cropY / sub,
            outW / sub, outH / sub,
            dst.buffer, dst.rowStride, dst.pixelStride,
        )
    }
}

private fun copyPlane(
    frame: ByteArray,
    src: SourcePlane,
    canvasW: Int,
    canvasH: Int,
    cropX: Int,
    cropY: Int,
    outW: Int,
    outH: Int,
    dst: java.nio.ByteBuffer,
    dstRowStride: Int,
    dstPixelStride: Int,
) {
    val row = ByteArray(outW)
    val same = src.width == canvasW && src.height == canvasH
    val sx = src.width.toFloat() / canvasW
    val sy = src.height.toFloat() / canvasH

    for (y in 0 until outH) {
        if (same) {
            val base = src.offset + (y + cropY) * src.rowStride + cropX * src.pixelStride
            if (src.pixelStride == 1) {
                System.arraycopy(frame, base, row, 0, outW)
            } else {
                for (x in 0 until outW) row[x] = frame[base + x * src.pixelStride]
            }
        } else {
            val fy = ((y + cropY + 0.5f) * sy - 0.5f).coerceIn(0f, (src.height - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = minOf(y0 + 1, src.height - 1)
            val wy = fy - y0
            for (x in 0 until outW) {
                val fx = ((x + cropX + 0.5f) * sx - 0.5f).coerceIn(0f, (src.width - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = minOf(x0 + 1, src.width - 1)
                val wx = fx - x0
                val p00 = sample(frame, src, x0, y0)
                val p01 = sample(frame, src, x1, y0)
                val p10 = sample(frame, src, x0, y1)
                val p11 = sample(frame, src, x1, y1)
                val top = p00 + (p01 - p00) * wx
                val bottom = p10 + (p11 - p10) * wx
                row[x] = (top + (bottom - top) * wy + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }

        val at = y * dstRowStride
        if (dstPixelStride == 1) {
            dst.position(at)
            dst.put(row, 0, outW)
        } else {
            for (x in 0 until outW) dst.put(at + x * dstPixelStride, row[x])
        }
    }
}

private fun sample(frame: ByteArray, plane: SourcePlane, x: Int, y: Int): Float =
    (frame[plane.offset + y * plane.rowStride + x * plane.pixelStride].toInt() and 0xFF).toFloat()
