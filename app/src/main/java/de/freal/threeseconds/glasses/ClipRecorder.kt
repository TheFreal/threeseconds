package de.freal.threeseconds.glasses

import android.os.SystemClock
import android.util.Log
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DatError
import com.meta.wearable.dat.core.types.DatResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

sealed interface RecordResult {
    /** How long each step took and what the stream delivered, for the debug screen. */
    val timings: String

    data class Success(
        val file: File,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val frameCount: Int,
        val deviceName: String?,
        override val timings: String,
    ) : RecordResult

    data class Failure(val reason: String, override val timings: String = "") : RecordResult
}

/**
 * Captures a short clip from the glasses camera.
 *
 * The DAT SDK has no "record to file" call -- video only exists as a live stream over
 * Bluetooth -- so a clip is that stream run for [durationMs], then encoded on the phone.
 *
 * The SDK decodes the stream itself and hands over finished pictures. Passing its HEVC
 * through untouched looked cheaper, but a single lost or overwritten frame corrupted every
 * frame after it up to the next keyframe. The pictures are spooled to disk while the
 * camera runs, the camera is shut off the moment enough has arrived, and only then are
 * they encoded, at leisure and at a high bitrate.
 *
 * Duration is measured from frame timestamps rather than wall clock, so the Bluetooth
 * connection warming up does not eat into the three seconds.
 */
class ClipRecorder(
    private val outputFile: File,
    private val durationMs: Long = 3_000L,
    private val quality: VideoQuality = VideoQuality.MEDIUM,
    private val frameRate: Int = 24,
) {

    private val phases = Phases()
    private var deviceName: String? = null

    suspend fun record(): RecordResult {
        val spool = FrameSpool(File(outputFile.parentFile, outputFile.name + ".yuv"))
        try {
            val failure = capture(spool)
            if (failure != null) return failure

            val result = withContext(Dispatchers.Default) { ClipEncoder(spool).encode(outputFile) }
            phases.mark("encode")
            if (result == null) return fail("The video from the glasses could not be encoded")

            return RecordResult.Success(
                file = outputFile,
                width = result.width,
                height = result.height,
                durationMs = result.durationMs,
                frameCount = result.frameCount,
                deviceName = deviceName,
                timings = phases.summary() + "\n" + streamSummary(spool) + "\n" + result.summary,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Recording failed", e)
            return fail(e.message?.takeIf { it.isNotBlank() } ?: "Recording failed (${e.javaClass.simpleName})")
        } finally {
            spool.close()
        }
    }

    /** Runs the camera until [spool] holds the clip. Returns null on success. */
    private suspend fun capture(spool: FrameSpool): RecordResult.Failure? {
        // Unknown (glasses not connected) carries on, so the failure below names the real problem.
        if (GlassesManager.cameraPermission() == false) {
            return fail("Camera access for the glasses has not been granted")
        }

        // DatResult.fold/onFailure are ordinary (non-inline) functions, so a bail-out
        // has to go through getOrNull()/errorOrNull() rather than a non-local return.
        val sessionResult = Wearables.createSession(AutoDeviceSelector())
        val session = sessionResult.getOrNull()
            ?: return fail(sessionResult.describeError("Could not reach the glasses"))

        var camera: Camera? = null
        try {
            session.start()
            val started = withTimeoutOrNull(SESSION_TIMEOUT_MS) {
                session.state.first { it == DeviceSessionState.STARTED }
            }
            phases.mark("session")
            if (started == null) return fail("The glasses did not come online in time")
            deviceName = session.deviceInfo.value.name

            // compressVideo = false: the SDK decodes, see the class comment.
            val config = StreamConfiguration(videoQuality = quality, frameRate = frameRate, compressVideo = false)
            val cameraResult = session.addCamera(config)
            val addedCamera = cameraResult.getOrNull()
                ?: return fail(cameraResult.describeError("The camera could not be attached"))
            camera = addedCamera

            val stream = addedCamera.stream
            val startResult = stream.start()
            if (startResult.isFailure) {
                return fail(startResult.describeError("The camera stream would not start"))
            }
            val streaming = withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                stream.state.first { it == StreamState.STREAMING }
            }
            phases.mark("stream")
            if (streaming == null) return fail("The camera stream did not start in time")

            val targetUs = durationMs * 1000
            val frameUs = 1_000_000L / frameRate
            var firstPts = -1L
            val completed = withTimeoutOrNull(captureTimeoutMs()) {
                stream.videoStream.first { frame ->
                    if (frame.isCompressed || frame.isCodecConfig) return@first false
                    if (firstPts < 0) {
                        firstPts = frame.presentationTimeUs
                        phases.mark("first frame")
                    }
                    spool.append(frame.buffer, frame.width, frame.height, frame.presentationTimeUs)
                    // The last frame shows for one frame interval, so it completes the clip.
                    frame.presentationTimeUs - firstPts + frameUs >= targetUs
                }
                true
            }
            phases.mark("capture")

            if (completed == null) {
                if (firstPts < 0) return fail("No video arrived from the glasses")
                // A link that stalls part way through leaves a few frames that are not worth
                // saving as the day's clip; only keep a capture that reached half the target.
                val gotUs = spool.entries.last().ptsUs - firstPts + frameUs
                if (gotUs < targetUs / 2) return fail("The glasses stopped sending video")
                Log.w(TAG, "Capture timed out with ${gotUs / 1000}ms; keeping what we got")
            }
            return null
        } finally {
            // Off before encoding, so the capture light goes out as soon as possible.
            runCatching { camera?.stop() }
            runCatching { session.removeCamera() }
            runCatching { session.stop() }
            phases.mark("stop")
        }
    }

    /** Frames, rate and hiccups as they arrived from the glasses. */
    private fun streamSummary(spool: FrameSpool): String {
        val entries = spool.entries
        if (entries.size < 2) return "${entries.size} frame"
        val spanUs = entries.last().ptsUs - entries.first().ptsUs
        val fps = (entries.size - 1) * 1_000_000f / spanUs.coerceAtLeast(1)
        val nominalUs = 1_000_000L / frameRate
        val gaps = entries.zipWithNext().count { (a, b) -> b.ptsUs - a.ptsUs > nominalUs * 3 / 2 }
        val sizes = entries.map { "${it.width}x${it.height}" }.distinct()
        return "${entries.size} frames at %.1f fps, $gaps ${if (gaps == 1) "gap" else "gaps"}".format(fps) +
            if (sizes.size > 1) ", sizes ${sizes.joinToString(" → ")}" else ""
    }

    private fun fail(reason: String) = RecordResult.Failure(reason, phases.summary())

    /** Allow generous slack over the target so a stuttering link can still finish. */
    private fun captureTimeoutMs(): Long = durationMs * 4 + 5_000L

    private fun <T, E : DatError> DatResult<T, E>.describeError(fallback: String): String =
        errorOrNull()?.description ?: exceptionOrNull()?.message ?: fallback

    private companion object {
        const val TAG = "ClipRecorder"
        const val SESSION_TIMEOUT_MS = 25_000L
        const val STREAM_TIMEOUT_MS = 25_000L
    }
}

/** Wall-clock time of each step of a recording. */
private class Phases {
    private val start = SystemClock.elapsedRealtime()
    private var last = start
    private val parts = mutableListOf<String>()

    fun mark(name: String) {
        val now = SystemClock.elapsedRealtime()
        parts += "$name ${seconds(now - last)}"
        last = now
    }

    fun summary(): String =
        if (parts.isEmpty()) "" else parts.joinToString(" · ") + " · total ${seconds(last - start)}"

    private fun seconds(ms: Long) = "%.1fs".format(ms / 1000f)
}
