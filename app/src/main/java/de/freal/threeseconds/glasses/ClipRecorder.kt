package de.freal.threeseconds.glasses

import android.util.Log
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DatError
import com.meta.wearable.dat.core.types.DatResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

sealed interface RecordResult {
    data class Success(
        val file: File,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val frameCount: Int,
        val deviceName: String?,
    ) : RecordResult

    data class Failure(val reason: String) : RecordResult
}

/**
 * Captures a short clip from the glasses camera.
 *
 * The DAT SDK has no "record to file" call -- it exposes a frame stream -- so a clip
 * is the stream run for [durationMs] with every access unit handed to [Mp4FrameWriter],
 * which muxes them once the stream has stopped.
 * Duration is measured from frame timestamps rather than wall clock, so the Bluetooth
 * connection warming up does not eat into the three seconds.
 */
class ClipRecorder(
    private val outputFile: File,
    private val durationMs: Long = 3_000L,
    private val quality: VideoQuality = VideoQuality.MEDIUM,
    private val frameRate: Int = 24,
) {

    suspend fun record(): RecordResult {
        if (!GlassesManager.hasCameraPermission()) {
            return RecordResult.Failure("Camera access for the glasses has not been granted")
        }

        // DatResult.fold/onFailure are ordinary (non-inline) functions, so a bail-out
        // has to go through getOrNull()/errorOrNull() rather than a non-local return.
        val sessionResult = Wearables.createSession(AutoDeviceSelector())
        val session = sessionResult.getOrNull()
            ?: return RecordResult.Failure(sessionResult.describeError("Could not reach the glasses"))

        var camera: Camera? = null
        try {
            session.start()

            val started = withTimeoutOrNull(SESSION_TIMEOUT_MS) {
                session.state.first { it == DeviceSessionState.STARTED }
            }
            if (started == null) {
                return RecordResult.Failure("The glasses did not come online in time")
            }

            val config = StreamConfiguration(
                videoQuality = quality,
                frameRate = frameRate,
                compressVideo = true,
            )
            val cameraResult = session.addCamera(config)
            val addedCamera = cameraResult.getOrNull()
                ?: return RecordResult.Failure(cameraResult.describeError("The camera could not be attached"))
            camera = addedCamera

            val stream = addedCamera.stream
            val startResult = stream.start()
            if (startResult.isFailure) {
                return RecordResult.Failure(startResult.describeError("The camera stream would not start"))
            }

            val streaming = withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                stream.state.first { it == StreamState.STREAMING }
            }
            if (streaming == null) {
                return RecordResult.Failure("The camera stream did not start in time")
            }

            val writer = Mp4FrameWriter(outputFile, frameRate)
            try {
                val completed = withTimeoutOrNull(captureTimeoutMs()) {
                    stream.videoStream.first { frame ->
                        writer.onFrame(frame)
                        writer.durationMs >= durationMs
                    }
                    true
                }

                if (completed == null) {
                    // A link that stalls part way through leaves a few frames that are
                    // not worth saving as the day's clip; only keep a capture that reached
                    // at least half the target.
                    if (writer.durationMs < durationMs / 2) {
                        return RecordResult.Failure("The glasses stopped sending video")
                    }
                    Log.w(TAG, "Capture timed out with ${writer.durationMs}ms; keeping what we got")
                }

                if (!writer.finish()) {
                    return RecordResult.Failure("No video arrived from the glasses")
                }
                return RecordResult.Success(
                    file = outputFile,
                    width = writer.width,
                    height = writer.height,
                    durationMs = writer.durationMs,
                    frameCount = writer.frameCount,
                    deviceName = session.deviceInfo.value.name,
                )
            } finally {
                writer.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording failed", e)
            return RecordResult.Failure(e.message ?: "Recording failed")
        } finally {
            runCatching { camera?.stop() }
            runCatching { session.removeCamera() }
            runCatching { session.stop() }
        }
    }

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
