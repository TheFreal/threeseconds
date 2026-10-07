package de.freal.threeseconds.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.camera.types.VideoQuality
import de.freal.threeseconds.container
import de.freal.threeseconds.data.Clip
import de.freal.threeseconds.data.DayLog
import de.freal.threeseconds.data.DayStatus
import de.freal.threeseconds.data.computeStreak
import de.freal.threeseconds.data.key
import de.freal.threeseconds.glasses.ClipRecorder
import de.freal.threeseconds.glasses.RecordResult
import de.freal.threeseconds.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * Runs the actual three second capture.
 *
 * Started from the notification action, which on Android 12+ is an exemption from the
 * background foreground-service launch restriction -- that is what lets a tap on the
 * watch kick this off while the phone is asleep in a pocket.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startForegroundCompat("Connecting to your glasses")) {
            // Without a foreground slot the system would kill us anyway; leave quietly.
            stopSelf()
            return START_NOT_STICKY
        }

        if (job?.isActive == true) {
            Log.i(TAG, "Capture already running; ignoring duplicate tap")
            return START_NOT_STICKY
        }

        job = scope.launch {
            try {
                capture()
            } catch (e: Exception) {
                Log.e(TAG, "Capture failed", e)
                finishWithFailure(e.message ?: "Something went wrong")
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun capture() {
        val container = applicationContext.container
        val settings = container.settings.current()
        val recordedAt = System.currentTimeMillis()
        val temp = File(cacheDir, "capture_${UUID.randomUUID()}.mp4")

        val result = ClipRecorder(
            outputFile = temp,
            durationMs = settings.clipDurationMs,
            quality = runCatching { VideoQuality.valueOf(settings.videoQuality) }
                .getOrDefault(VideoQuality.MEDIUM),
            frameRate = settings.frameRate,
        ).record()

        when (result) {
            is RecordResult.Failure -> {
                temp.delete()
                finishWithFailure(result.reason)
            }

            is RecordResult.Success -> {
                val uri = container.clipStore.save(
                    source = result.file,
                    recordedAt = recordedAt,
                    width = result.width,
                    height = result.height,
                    durationMs = result.durationMs,
                )
                temp.delete()

                val day = LocalDate.now().key()
                container.database.clips().insert(
                    Clip(
                        id = UUID.randomUUID().toString(),
                        uri = uri.toString(),
                        recordedAt = recordedAt,
                        day = day,
                        durationMs = result.durationMs,
                        width = result.width,
                        height = result.height,
                        deviceName = result.deviceName,
                    )
                )
                container.database.days().upsert(
                    DayLog(day = day, status = DayStatus.RECORDED, updatedAt = recordedAt)
                )

                val streak = computeStreak(container.database.days().recent(400)).current
                Notifications.showResult(
                    applicationContext,
                    "Got it",
                    if (streak > 1) "$streak days in a row." else "Three seconds saved.",
                )
                container.scheduler.ensureScheduled(force = true, skipToday = true)
                Log.i(TAG, "Saved ${result.frameCount} frames / ${result.durationMs}ms to $uri")
            }
        }
    }

    /**
     * A failed capture still counts as showing up, so the day is logged as ATTEMPTED
     * and the streak survives.
     */
    private suspend fun finishWithFailure(reason: String) {
        val container = applicationContext.container
        val day = LocalDate.now().key()

        val existing = container.database.days().forDay(day)
        if (existing?.status != DayStatus.RECORDED) {
            container.database.days().upsert(
                DayLog(day = day, status = DayStatus.ATTEMPTED, updatedAt = System.currentTimeMillis())
            )
        }

        Notifications.showResult(applicationContext, "Couldn't record", "$reason. Your streak is safe.")

        // Offer another shot later today, but through the same capped budget as a
        // missed sample -- otherwise repeated hardware failures could re-prompt forever.
        if (!container.scheduler.rerollWithinToday()) {
            container.scheduler.ensureScheduled(force = true, skipToday = true)
        }
    }

    private fun startForegroundCompat(text: String): Boolean =
        ForegroundStart.start(this, Notifications.ID_STATUS, Notifications.buildStatus(this, text))

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecordingService"

        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.e(TAG, "Could not start recording service", it) }
        }
    }
}
