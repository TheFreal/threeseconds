package de.freal.threeseconds.glasses

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Decoded frames on disk, in arrival order.
 *
 * A few seconds of raw YUV is tens to hundreds of megabytes -- too much to hold in memory
 * at HIGH -- but flash writes it far faster than the glasses can send it. So capture only
 * appends, and encoding reads the frames back once the camera is off.
 */
internal class FrameSpool(private val file: File) : Closeable {

    class Entry(val offset: Long, val size: Int, val width: Int, val height: Int, val ptsUs: Long)

    private val channel = RandomAccessFile(file, "rw").channel
    private var end = 0L

    val entries = ArrayList<Entry>(128)

    val largestFrame: Int get() = entries.maxOfOrNull { it.size } ?: 0

    fun append(buffer: ByteBuffer, width: Int, height: Int, ptsUs: Long) {
        val src = buffer.duplicate()
        val size = src.remaining()
        if (size <= 0) return
        var at = end
        while (src.hasRemaining()) at += channel.write(src, at)
        entries += Entry(end, size, width, height, ptsUs)
        end = at
    }

    /** Reads [entry] into the start of [into], which must hold at least [Entry.size] bytes. */
    fun read(entry: Entry, into: ByteArray) {
        val dst = ByteBuffer.wrap(into, 0, entry.size)
        var at = entry.offset
        while (dst.hasRemaining()) {
            val n = channel.read(dst, at)
            if (n < 0) error("Frame spool ended early")
            at += n
        }
    }

    override fun close() {
        runCatching { channel.close() }
        file.delete()
    }
}
