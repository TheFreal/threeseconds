package de.freal.threeseconds

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.freal.threeseconds.data.Clip
import de.freal.threeseconds.montage.MontageBuilder
import de.freal.threeseconds.montage.MontageResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Checks that stitching clips end to end yields one playable file of the summed length. */
@RunWith(AndroidJUnit4::class)
class MontageBuilderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val temps = mutableListOf<File>()

    @After
    fun tearDown() {
        temps.forEach { it.delete() }
    }

    private fun fixture(name: String, seconds: Int): File =
        File(context.cacheDir, name).also {
            it.delete()
            HevcFixture.create(it, seconds = seconds)
            temps += it
        }

    @Test
    fun stitchesClipsIntoOnePlayableFile() = runBlocking {
        val a = fixture("m_a.mp4", seconds = 3)
        val b = fixture("m_b.mp4", seconds = 3)
        val c = fixture("m_c.mp4", seconds = 3)

        val clips = listOf(a, b, c).mapIndexed { i, f ->
            Clip(
                id = "clip-$i",
                uri = Uri.fromFile(f).toString(),
                recordedAt = 1_000L * i,
                day = "2026-10-0${i + 1}",
                durationMs = 3_000,
                width = 504,
                height = 896,
                deviceName = "Mock",
            )
        }

        val output = File(context.cacheDir, "montage.mp4").also { it.delete(); temps += it }
        var lastProgress = 0
        val result = MontageBuilder(context).build(clips, output) { done, _ -> lastProgress = done }

        assertTrue(
            "Montage failed: ${(result as? MontageResult.Failure)?.reason}",
            result is MontageResult.Success,
        )
        result as MontageResult.Success

        assertEquals(3, result.clipCount)
        assertEquals(0, result.skipped)
        assertEquals(3, lastProgress)
        assertTrue("Output missing", output.exists() && output.length() > 0)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.absolutePath)
            assertTrue("No track in the montage", extractor.trackCount > 0)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC, format.getString(MediaFormat.KEY_MIME))

            // Three ~3s clips should land near 9s, not 3s (which would mean the
            // timestamps all collapsed onto each other).
            val durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000
            assertTrue("Expected roughly 9s, got ${durationMs}ms", durationMs in 7_000..11_000)

            extractor.selectTrack(0)
            var samples = 0
            var lastPts = -1L
            val buf = java.nio.ByteBuffer.allocate(1 shl 20)
            while (extractor.readSampleData(buf, 0) >= 0) {
                val pts = extractor.sampleTime
                assertTrue("Timestamps went backwards at sample $samples", pts >= lastPts)
                lastPts = pts
                samples++
                extractor.advance()
            }
            assertTrue("Expected many samples, read $samples", samples > 30)
        } finally {
            extractor.release()
        }
    }

    @Test
    fun reportsFailureForAnEmptyMonth() = runBlocking {
        val output = File(context.cacheDir, "empty.mp4").also { temps += it }
        val result = MontageBuilder(context).build(emptyList(), output)
        assertTrue(result is MontageResult.Failure)
    }
}
