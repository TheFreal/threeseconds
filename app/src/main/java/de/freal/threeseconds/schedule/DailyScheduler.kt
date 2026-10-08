package de.freal.threeseconds.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import de.freal.threeseconds.data.AppSettings
import de.freal.threeseconds.data.AttemptKind
import de.freal.threeseconds.data.SettingsRepository
import de.freal.threeseconds.data.appToday
import de.freal.threeseconds.data.atMinute
import de.freal.threeseconds.data.key
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Picks one random moment per day and arms an exact alarm for it.
 *
 * The moment is only the *earliest* the app will ask: when the alarm fires the glasses
 * may well be in a pocket, so [DonWatchService] takes over and waits for them to go on
 * before anything is shown. Firing from an exact alarm also exempts that service start
 * from the Android 12+ background foreground-service restriction.
 */
class DailyScheduler(
    private val context: Context,
    private val settings: SettingsRepository,
    private val attemptLog: AttemptLog,
) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /**
     * Makes sure a moment is armed for today, choosing a new one if the stored
     * schedule is stale. Safe to call repeatedly.
     */
    suspend fun ensureScheduled(force: Boolean = false, skipToday: Boolean = false) {
        val current = settings.current()
        if (!current.enabled) {
            cancel()
            attemptLog.cancelled()
            return
        }

        // A stored moment that has not passed yet is still the plan. Re-arming it is
        // idempotent (same PendingIntent, same time) and is what makes this safe to
        // call on every app start, while also restoring the alarm after a reboot --
        // alarms do not survive one, but the chosen moment must.
        if (!force && current.scheduledAtMillis > now()) {
            arm(current.scheduledAtMillis)
            return
        }

        val target = pickMoment(current, appToday(), skipToday) ?: return
        settings.setSchedule(target.day, target.atMillis)
        arm(target.atMillis)
        attemptLog.scheduled(AttemptKind.FIRST, target.atMillis)
        Log.i(TAG, "Daily prompt armed for ${at(target.atMillis)}")
    }

    private data class Moment(val day: String, val atMillis: Long)

    /**
     * The first attempt of a day, or tomorrow's when today has no room left.
     *
     * The first attempt is drawn from the *front* of the window rather than from the
     * whole of it. Drawing uniformly across the day meant a single unlucky late draw
     * could skip the entire day and then cram every retry into the last hour; starting
     * near the front and stepping forward keeps the attempts spread across the window
     * no matter where the dice land.
     */
    private fun pickMoment(current: AppSettings, today: LocalDate, skipToday: Boolean = false): Moment? {
        if (!skipToday) {
            firstMomentToday(current, today)?.let { return Moment(today.key(), it) }
        }

        val tomorrow = today.plusDays(1)
        val start = windowStartMs(current, tomorrow)
        val spread = spreadMs(current, tomorrow)
        return Moment(tomorrow.key(), randomBetween(start, start + spread))
    }

    /** Random instant in the opening stretch of today's window. */
    private fun firstMomentToday(current: AppSettings, today: LocalDate): Long? {
        val windowStart = windowStartMs(current, today)
        val windowEnd = windowEndMs(current, today)
        if (windowEnd <= windowStart) {
            Log.w(TAG, "Window is empty; not scheduling")
            return null
        }

        val lo = maxOf(windowStart, now() + LEAD_MS)
        val hi = minOf(windowStart + spreadMs(current, today), windowEnd)
        // Past the opening stretch already (a mid-window install, say): fall in step
        // with the normal retry cadence instead of skipping the rest of the day.
        return if (lo < hi) randomBetween(lo, hi) else nextMomentToday(current, today, now())
    }

    /**
     * Random instant inside the step that follows [anchorMs].
     *
     * The anchor is the previous attempt, so "within the next step" is measured from
     * that attempt rather than from whenever this happens to run -- which keeps the
     * cadence monotonic. Clamping the anchor to at least now() means a Doze-deferred
     * alarm resumes the cadence from where it actually woke up instead of trying to
     * schedule into the past.
     */
    private fun nextMomentToday(current: AppSettings, today: LocalDate, anchorMs: Long): Long? {
        val windowEnd = windowEndMs(current, today)
        val anchor = maxOf(anchorMs, now())
        val lo = anchor + LEAD_MS
        val hi = minOf(anchor + stepMs(current, today), windowEnd)
        return if (lo < hi) randomBetween(lo, hi) else null
    }

    private fun windowStartMs(current: AppSettings, day: LocalDate): Long = day.atMinute(current.windowStartMinute)

    private fun windowEndMs(current: AppSettings, day: LocalDate): Long = day.atMinute(current.windowEndMinute)

    /** Clamped so a window shorter than the configured spread still behaves. */
    private fun spreadMs(current: AppSettings, day: LocalDate): Long {
        val windowLength = windowEndMs(current, day) - windowStartMs(current, day)
        return minOf(current.firstAttemptSpreadMinutes * 60_000L, windowLength)
    }

    private fun stepMs(current: AppSettings, day: LocalDate): Long {
        val windowLength = windowEndMs(current, day) - windowStartMs(current, day)
        return minOf(current.retryStepMinutes * 60_000L, windowLength).coerceAtLeast(LEAD_MS * 2)
    }

    /**
     * Picks the next attempt after one found the glasses off.
     *
     * Each re-roll is a fresh random draw inside the next step of the window, so the
     * attempts march evenly across the day while no single one is predictable. Returns
     * false once the window closes, which is the day's real limit.
     */
    suspend fun rerollWithinToday(): Boolean {
        val current = settings.current()
        val attempts = current.attemptCount + 1
        if (attempts >= AppSettings.MAX_ATTEMPTS_PER_DAY) {
            Log.w(TAG, "Hit the attempt safety cap")
            return false
        }

        // Anchored on the attempt that just failed, so each re-roll steps forward by at
        // most one configured step rather than subdividing whatever is left of the day.
        val next = nextMomentToday(current, appToday(), current.scheduledAtMillis)
            ?: return false
        settings.setAttemptCount(attempts)
        settings.setNextAt(next)
        arm(next)
        attemptLog.scheduled(AttemptKind.RETRY, next)
        Log.i(TAG, "Re-rolled to ${at(next)} (attempt ${attempts + 1})")
        return true
    }

    /** Arms the deadline that closes an unanswered prompt. */
    fun armPromptExpiry(atMillis: Long) {
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, expiryIntent())
        } catch (e: SecurityException) {
            Log.e(TAG, "Not allowed to arm the prompt expiry", e)
        }
    }

    fun cancelPromptExpiry() {
        alarmManager.cancel(expiryIntent())
    }

    private fun at(millis: Long) =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault())

    private fun randomBetween(fromMs: Long, toMs: Long): Long =
        if (toMs <= fromMs) fromMs else Random.nextLong(fromMs, toMs)

    /**
     * Re-arms the prompt [minutes] from now, keeping the day and the snooze count.
     * The new time is persisted so a later [ensureScheduled] respects the snooze
     * instead of restoring the original moment.
     */
    suspend fun scheduleSnooze(minutes: Long) {
        val at = now() + minutes * 60_000L
        settings.setNextAt(at)
        arm(at)
        attemptLog.scheduled(AttemptKind.SNOOZE, at)
        Log.i(TAG, "Snoozed until ${at(at)}")
    }

    fun cancel() {
        alarmManager.cancel(pendingIntent())
    }

    /** Without this the prompt still fires in Doze, just with the system's own slack. */
    fun exactAlarmsAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun arm(atMillis: Long) {
        val pi = pendingIntent()
        val canExact = exactAlarmsAllowed()
        try {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            } else {
                // Still fires in Doze, just with the system's own slack.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Not allowed to schedule alarms", e)
        }
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQ_DAILY,
        Intent(context, DailyTriggerReceiver::class.java).setAction(DailyTriggerReceiver.ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun expiryIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQ_EXPIRY,
        Intent(context, DailyTriggerReceiver::class.java)
            .setAction(DailyTriggerReceiver.ACTION_PROMPT_EXPIRED),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val TAG = "DailyScheduler"
        const val REQ_DAILY = 20
        const val REQ_EXPIRY = 21

        /** Never arm an alarm for the instant we are already at. */
        const val LEAD_MS = 60_000L
    }
}
