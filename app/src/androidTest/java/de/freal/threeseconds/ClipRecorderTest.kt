package de.freal.threeseconds

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.core.types.DonState
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import de.freal.threeseconds.glasses.ClipRecorder
import de.freal.threeseconds.glasses.GlassesManager
import de.freal.threeseconds.glasses.RecordResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End to end check of the capture path against MockDeviceKit: synthetic HEVC in,
 * playable .mp4 out. This covers the piece with no safety net -- the SDK hands over
 * raw HEVC access units and we mux them ourselves.
 */
@RunWith(AndroidJUnit4::class)
class ClipRecorderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val mockKit = MockDeviceKit.getInstance(context)

    private lateinit var feed: File
    private lateinit var output: File

    @Before
    fun setUp() = runBlocking {
        GlassesManager.initialize(context)

        feed = File(context.cacheDir, "feed.mp4").also { if (it.exists()) it.delete() }
        HevcFixture.create(feed)

        output = File(context.cacheDir, "out.mp4").also { if (it.exists()) it.delete() }

        mockKit.enable(MockDeviceKitConfig(initiallyRegistered = true, initialPermissionsGranted = true))
        val glasses = mockKit.pairGlasses(GlassesModel.RAYBAN_META).getOrThrow()
        glasses.powerOn()
        glasses.unfold()
        glasses.don()
        glasses.services.camera.setCameraFeed(Uri.fromFile(feed))

        // On the first run after an install the SDK needs a moment to come up, and test
        // methods do not run in a guaranteed order -- so wait for the preconditions here
        // rather than letting a cold start race the recorder's own timeouts.
        withTimeoutOrNull(20_000) {
            GlassesManager.registrationState.first { it == RegistrationState.REGISTERED }
        }
        withTimeoutOrNull(20_000) { GlassesManager.status.first { it.connected && it.worn } }
        Unit
    }

    @After
    fun tearDown() {
        mockKit.disable()
        feed.delete()
        output.delete()
    }

    @Test
    fun mockGlassesReportWornAndRegistered() = runBlocking {
        val registered = withTimeoutOrNull(10_000) {
            GlassesManager.registrationState.first { it == RegistrationState.REGISTERED }
        }
        assertEquals(RegistrationState.REGISTERED, registered)

        // The trigger samples this rather than waiting for a DONNED transition, so the
        // sample has to come back correct within its short settle budget.
        val status = GlassesManager.sampleStatus(settleMs = 15_000, wornGraceMs = 5_000)
        assertTrue("Expected the mock glasses to report DONNED", status.worn)
    }

    @Test
    fun recordsAThreeSecondPlayableMp4() = runBlocking {
        val result = ClipRecorder(
            outputFile = output,
            durationMs = 3_000L,
            frameRate = 24,
        ).record()

        assertTrue(
            "Recording failed: ${(result as? RecordResult.Failure)?.reason}",
            result is RecordResult.Success,
        )
        result as RecordResult.Success

        assertTrue("Output file is missing", output.exists())
        assertTrue("Output file is empty", output.length() > 0)
        assertTrue("Expected several frames, got ${result.frameCount}", result.frameCount > 10)

        // The file must be readable as a real HEVC video track, not just bytes on disk.
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.absolutePath)
            assertTrue("No tracks in the output", extractor.trackCount > 0)

            val format = extractor.getTrackFormat(0)
            assertEquals(
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                format.getString(MediaFormat.KEY_MIME),
            )

            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            assertTrue(
                "Expected roughly 3s, got ${durationUs / 1000}ms",
                durationUs in 2_000_000..5_000_000,
            )

            // Walk every sample so a structurally broken file surfaces here.
            extractor.selectTrack(0)
            var samples = 0
            val buf = java.nio.ByteBuffer.allocate(1 shl 20)
            while (extractor.readSampleData(buf, 0) >= 0) {
                samples++
                extractor.advance()
            }
            assertTrue("Expected many samples, read $samples", samples > 10)
        } finally {
            extractor.release()
        }
    }
}
