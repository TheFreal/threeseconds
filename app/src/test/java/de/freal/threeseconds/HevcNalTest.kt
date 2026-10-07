package de.freal.threeseconds

import de.freal.threeseconds.glasses.HevcNal
import de.freal.threeseconds.glasses.Sample
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte layouts taken from clips recorded on real glasses, where every access unit
 * arrives with a transport header and zero padding ahead of the first start code.
 */
class HevcNalTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private val sc = bytes(0, 0, 0, 1)
    private val vps = bytes(0x40, 0x01, 0x0c, 0x01, 0xff)
    private val sps = bytes(0x42, 0x01, 0x01, 0x01, 0x60)
    private val pps = bytes(0x44, 0x01, 0xc0, 0xe3, 0x0f)
    private val idr = bytes(0x26, 0x01, 0xaf, 0x33, 0x10)
    private val trail = bytes(0x02, 0x01, 0xd0, 0x0f, 0x22)

    private fun header(seq: Int) =
        bytes(0x00, 0x75, 0x00, seq, 0x80, 0x60, 0x00, seq - 1, 0x27, 0x16, 0xfc, seq, 0, 0, 0, 0)

    private fun annexB(vararg nals: ByteArray) = nals.fold(ByteArray(0)) { acc, n -> acc + sc + n }

    @Test
    fun stripsTransportHeaderAndPadding() {
        val raw = header(1) + annexB(vps, sps, pps, idr)
        assertArrayEquals(annexB(vps, sps, pps, idr), HevcNal.normalize(raw))
    }

    @Test
    fun leavesCleanAnnexBAlone() {
        val clean = annexB(trail)
        assertArrayEquals(clean, HevcNal.normalize(clean))
    }

    @Test
    fun dropsEmptyAndInvalidNalUnits() {
        val raw = sc + sc + trail + sc + bytes(0x80, 0x01, 0x55) + sc + bytes(0x02, 0x00, 0x55)
        assertArrayEquals(annexB(trail), HevcNal.normalize(raw))
    }

    @Test
    fun rewritesThreeByteStartCodes() {
        val raw = bytes(0, 0, 1) + trail
        assertArrayEquals(annexB(trail), HevcNal.normalize(raw))
    }

    @Test
    fun headerOnlyBufferHasNothingUsable() {
        assertNull(HevcNal.normalize(header(3)))
    }

    @Test
    fun findsParameterSetsBehindHeader() {
        val raw = header(1) + annexB(vps, sps, pps, idr)
        assertArrayEquals(annexB(vps, sps, pps), HevcNal.parameterSets(raw))
        assertTrue(HevcNal.isKeyFrame(raw))
        assertFalse(HevcNal.isKeyFrame(header(2) + annexB(trail)))
    }

    @Test
    fun dropsTheEarlierOfTwoFramesWithTheSameHeader() {
        // Frame 0x33 was read after 0x34 had already been written into its buffer.
        val a = Sample(header(0x32) + annexB(trail), 1)
        val torn = Sample(header(0x34) + annexB(trail), 2)
        val b = Sample(header(0x34) + annexB(trail), 3)
        val c = Sample(header(0x35) + annexB(trail), 4)

        val kept = HevcNal.dropOverwritten(listOf(a, torn, b, c))
        assertEquals(listOf(1L, 3L, 4L), kept.map { it.ptsUs })
    }

    @Test
    fun neverDropsFramesWithoutTransportHeader() {
        val frames = (1..3).map { Sample(annexB(trail), it.toLong()) }
        assertEquals(3, HevcNal.dropOverwritten(frames).size)
    }
}
