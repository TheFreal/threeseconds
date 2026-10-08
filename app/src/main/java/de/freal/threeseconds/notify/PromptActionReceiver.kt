package de.freal.threeseconds.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.freal.threeseconds.container
import de.freal.threeseconds.data.AppSettings
import de.freal.threeseconds.data.AttemptText
import de.freal.threeseconds.data.DayLog
import de.freal.threeseconds.data.DayStatus
import de.freal.threeseconds.data.appToday
import de.freal.threeseconds.data.key
import de.freal.threeseconds.schedule.goAsyncScope
import de.freal.threeseconds.service.RecordingService

/**
 * Handles the two buttons on the daily prompt.
 *
 * This is a broadcast receiver rather than an activity so that tapping "Record 3s" on
 * the Pixel Watch runs straight here on the phone -- no "open on phone" hand-off, no
 * unlocking, no app window.
 */
class PromptActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        Notifications.cancel(app, Notifications.ID_PROMPT)
        val test = intent.getBooleanExtra(EXTRA_TEST, false)

        when (intent.action) {
            ACTION_RECORD -> {
                // The countdown is answered; stop it from closing the day behind us. A test
                // prompt records for real: testing the recording is the point.
                if (test) app.container.scheduler.cancelTestExpiry() else app.container.scheduler.cancelPromptExpiry()
                RecordingService.start(app, fromPrompt = true)
            }

            // A test snooze leaves the budget, the day and the real schedule alone and just
            // sends the test prompt again.
            ACTION_SNOOZE -> if (test) goAsyncScope {
                val container = app.container
                container.scheduler.cancelTestExpiry()
                container.attempts.respondToOpenPrompt(
                    AttemptText.SNOOZED_PREFIX + "${AppSettings.SNOOZE_MINUTES}m (test, nothing else changed)"
                )
                container.scheduler.armTestPrompt(System.currentTimeMillis() + AppSettings.SNOOZE_MINUTES * 60_000L)
            } else goAsyncScope {
                val container = app.container
                val current = container.settings.current()
                val used = current.snoozeCount + 1

                if (used > AppSettings.MAX_SNOOZES) {
                    Log.w(TAG, "Snooze budget already spent")
                    container.attempts.respondToOpenPrompt("Snooze tapped, but no snoozes were left")
                    return@goAsyncScope
                }

                container.attempts.respondToOpenPrompt(
                    AttemptText.SNOOZED_PREFIX +
                        "${AppSettings.SNOOZE_MINUTES}m ($used/${AppSettings.MAX_SNOOZES})"
                )

                container.scheduler.cancelPromptExpiry()
                container.settings.setSnoozeCount(used)
                container.database.days().upsert(
                    DayLog(
                        day = appToday().key(),
                        status = DayStatus.SNOOZED,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                // A fixed push-back, not a fresh random draw: snoozing should move the
                // prompt by a known amount rather than scatter it somewhere new.
                container.scheduler.scheduleSnooze(AppSettings.SNOOZE_MINUTES)
                Log.i(TAG, "Snoozed ${AppSettings.SNOOZE_MINUTES}m ($used/${AppSettings.MAX_SNOOZES})")
            }

            else -> Unit
        }
    }

    companion object {
        const val ACTION_RECORD = "de.freal.threeseconds.action.RECORD"
        const val ACTION_SNOOZE = "de.freal.threeseconds.action.SNOOZE"
        const val EXTRA_TEST = "test"
        private const val TAG = "PromptAction"
    }
}
