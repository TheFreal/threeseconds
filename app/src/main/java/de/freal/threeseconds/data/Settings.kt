package de.freal.threeseconds.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "three_seconds")

/** User-facing preferences plus the per-day scheduling state. */
data class AppSettings(
    val enabled: Boolean = true,
    /**
     * Minutes from the day's midnight bounding the window the prompt may fire in, between
     * [DAY_START_MINUTE] and [DAY_END_MINUTE]: values past 24:00 are after midnight.
     */
    val windowStartMinute: Int = 9 * 60,
    val windowEndMinute: Int = 21 * 60,
    val clipDurationMs: Long = 3_000L,

    /**
     * How far into the window the first attempt of the day may land, and how far apart
     * subsequent attempts may be. Both are durations relative to the window rather than
     * clock times, so changing the window needs no change here.
     */
    val firstAttemptSpreadMinutes: Int = 120,
    val retryStepMinutes: Int = 60,

    val videoQuality: String = "MEDIUM",
    val frameRate: Int = 24,

    // Per-day state.
    val scheduledDay: String = "",
    val scheduledAtMillis: Long = 0L,
    val snoozeCount: Int = 0,
    val attemptCount: Int = 0,
    val promptedDay: String = "",
) {
    val maxSnoozes: Int get() = MAX_SNOOZES

    companion object {
        const val MAX_SNOOZES = 2

        /** Snooze is a short, fixed push-back -- never a new random draw. */
        const val SNOOZE_MINUTES = 5L

        /** How long you have to act once the prompt lands. */
        const val COUNTDOWN_MS = 60_000L

        /**
         * Safety valve only. The real bound on attempts is the window itself: each
         * re-roll steps forward by at most [retryStepMinutes], so a day fits roughly
         * (window length / step) attempts. This just stops a pathological
         * configuration from looping.
         */
        const val MAX_ATTEMPTS_PER_DAY = 64
    }
}

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        // Clamped into the 05:00-to-05:00 day; windows saved before it existed could
        // start earlier.
        val windowStart = (p[KEY_WINDOW_START] ?: (9 * 60)).coerceIn(DAY_START_MINUTE, DAY_END_MINUTE - 1)
        AppSettings(
            enabled = p[KEY_ENABLED] ?: true,
            windowStartMinute = windowStart,
            windowEndMinute = (p[KEY_WINDOW_END] ?: (21 * 60)).coerceIn(windowStart + 1, DAY_END_MINUTE),
            clipDurationMs = p[KEY_DURATION] ?: 3_000L,
            firstAttemptSpreadMinutes = p[KEY_FIRST_SPREAD] ?: 120,
            retryStepMinutes = p[KEY_RETRY_STEP] ?: 60,
            videoQuality = p[KEY_QUALITY] ?: "MEDIUM",
            frameRate = p[KEY_FRAME_RATE] ?: 24,
            scheduledDay = p[KEY_SCHEDULED_DAY] ?: "",
            scheduledAtMillis = p[KEY_SCHEDULED_AT] ?: 0L,
            snoozeCount = p[KEY_SNOOZE_COUNT] ?: 0,
            attemptCount = p[KEY_ATTEMPT_COUNT] ?: 0,
            promptedDay = p[KEY_PROMPTED_DAY] ?: "",
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setEnabled(value: Boolean) = edit { it[KEY_ENABLED] = value }

    suspend fun setWindow(startMinute: Int, endMinute: Int) = edit {
        it[KEY_WINDOW_START] = startMinute
        it[KEY_WINDOW_END] = endMinute
    }

    suspend fun setQuality(quality: String, frameRate: Int) = edit {
        it[KEY_QUALITY] = quality
        it[KEY_FRAME_RATE] = frameRate
    }

    suspend fun setClipDuration(ms: Long) = edit { it[KEY_DURATION] = ms }

    /** Both are minutes relative to the window, not clock times. */
    suspend fun setAttemptCadence(firstSpreadMinutes: Int, retryStepMinutes: Int) = edit {
        it[KEY_FIRST_SPREAD] = firstSpreadMinutes
        it[KEY_RETRY_STEP] = retryStepMinutes
    }

    /**
     * Records the randomly chosen moment for [day] so a reboot can restore it, and
     * resets the per-day budgets. Use [setNextAt] to move an existing day's prompt.
     */
    suspend fun setSchedule(day: String, atMillis: Long) = edit {
        it[KEY_SCHEDULED_DAY] = day
        it[KEY_SCHEDULED_AT] = atMillis
        it[KEY_SNOOZE_COUNT] = 0
        it[KEY_ATTEMPT_COUNT] = 0
    }

    /** Moves the next prompt without touching the day or the snooze budget. */
    suspend fun setNextAt(atMillis: Long) = edit { it[KEY_SCHEDULED_AT] = atMillis }

    suspend fun setAttemptCount(value: Int) = edit { it[KEY_ATTEMPT_COUNT] = value }

    suspend fun setSnoozeCount(value: Int) = edit { it[KEY_SNOOZE_COUNT] = value }

    suspend fun setPromptedDay(day: String) = edit { it[KEY_PROMPTED_DAY] = day }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("enabled")
        val KEY_WINDOW_START = intPreferencesKey("window_start")
        val KEY_WINDOW_END = intPreferencesKey("window_end")
        val KEY_DURATION = longPreferencesKey("clip_duration_ms")
        val KEY_QUALITY = stringPreferencesKey("video_quality")
        val KEY_FIRST_SPREAD = intPreferencesKey("first_attempt_spread_minutes")
        val KEY_RETRY_STEP = intPreferencesKey("retry_step_minutes")
        val KEY_FRAME_RATE = intPreferencesKey("frame_rate")
        val KEY_SCHEDULED_DAY = stringPreferencesKey("scheduled_day")
        val KEY_SCHEDULED_AT = longPreferencesKey("scheduled_at")
        val KEY_SNOOZE_COUNT = intPreferencesKey("snooze_count")
        val KEY_ATTEMPT_COUNT = intPreferencesKey("attempt_count")
        val KEY_PROMPTED_DAY = stringPreferencesKey("prompted_day")
    }
}
