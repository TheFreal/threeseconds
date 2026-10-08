package de.freal.threeseconds.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.freal.threeseconds.container
import de.freal.threeseconds.data.AppSettings
import de.freal.threeseconds.data.AttemptOutcome
import de.freal.threeseconds.data.AttemptText
import de.freal.threeseconds.data.DayLog
import de.freal.threeseconds.data.DayStatus
import de.freal.threeseconds.data.computeStreak
import de.freal.threeseconds.data.appToday
import de.freal.threeseconds.data.key
import de.freal.threeseconds.glasses.GlassesManager
import de.freal.threeseconds.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Runs [block] off the main thread while holding the broadcast alive. */
internal fun BroadcastReceiver.goAsyncScope(block: suspend CoroutineScope.() -> Unit) {
    val result = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            block()
        } catch (e: Exception) {
            Log.e("Receivers", "Async receiver work failed", e)
        } finally {
            result.finish()
        }
    }
}

/**
 * Fires at the randomly chosen moment, and again when an unanswered prompt times out.
 *
 * The attempt is a sample, not a vigil: it reads whether the glasses are on right now
 * and either prompts or quietly picks another random moment later today. Nothing stays
 * resident in between, so a day costs a handful of alarms rather than hours of a
 * foreground service -- and because the moment is chosen independently of when you put
 * your glasses on, the prompt never arrives as a reaction to you donning them.
 */
class DailyTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_FIRE -> goAsyncScope { attempt(app) }
            ACTION_PROMPT_EXPIRED -> goAsyncScope { expire(app) }
            ACTION_TEST_FIRE -> goAsyncScope {
                val armedFor = intent.getLongExtra(EXTRA_ARMED_FOR, System.currentTimeMillis())
                // No glasses check and no day bookkeeping: just the prompt, as it would land.
                prompt(app, app.container, app.container.attempts.testFired(armedFor), test = true)
            }
            ACTION_TEST_EXPIRED -> goAsyncScope {
                Notifications.cancel(app, Notifications.ID_PROMPT)
                app.container.attempts.respondToOpenPrompt("Countdown ran out (test, day untouched)")
            }
        }
    }

    private suspend fun attempt(app: Context) {
        val container = app.container
        val log = container.attempts
        val id = log.fired()
        try {
            attempt(app, container, id)
        } catch (e: Exception) {
            log.resolve(id, AttemptOutcome.FAILED, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private suspend fun attempt(app: Context, container: de.freal.threeseconds.AppContainer, id: Long) {
        val log = container.attempts
        if (!container.settings.current().enabled) {
            log.resolve(id, AttemptOutcome.DISABLED, "The daily prompt is switched off")
            return
        }

        val today = appToday().key()
        if (container.database.days().forDay(today)?.status == DayStatus.RECORDED) {
            Log.i(TAG, "Already recorded today; arming tomorrow")
            log.resolve(id, AttemptOutcome.ALREADY_RECORDED, "Today already has a clip")
            container.scheduler.ensureScheduled(force = true, skipToday = true)
            return
        }

        val glasses = GlassesManager.sampleStatus()
        if (!glasses.worn) {
            Log.i(TAG, "Glasses are not on (connected=${glasses.connected}); re-rolling")
            val why = if (glasses.connected) AttemptText.NOT_WORN else AttemptText.NOT_CONNECTED
            // Resolve before re-rolling: arming the next moment closes any row still pending.
            log.resolve(id, AttemptOutcome.NOT_WORN, why)
            if (!container.scheduler.rerollWithinToday()) {
                log.resolve(id, AttemptOutcome.NOT_WORN, "$why; no time left in today's window")
                closeUnpromptedDay(container)
            }
            return
        }

        prompt(app, container, id)
    }

    private suspend fun prompt(
        app: Context,
        container: de.freal.threeseconds.AppContainer,
        id: Long,
        test: Boolean = false,
    ) {
        val settings = container.settings.current()
        val streak = computeStreak(container.database.days().recent(400)).current
        val deadline = System.currentTimeMillis() + AppSettings.COUNTDOWN_MS

        if (test) {
            container.scheduler.armTestExpiry(deadline)
        } else {
            container.settings.setPromptedDay(appToday().key())
            container.scheduler.armPromptExpiry(deadline)
        }
        Notifications.notify(
            app,
            Notifications.ID_PROMPT,
            Notifications.buildPrompt(app, settings.snoozeCount, streak, deadline, test),
        )
        Log.i(TAG, "Prompt posted with a ${AppSettings.COUNTDOWN_MS / 1000}s countdown")

        val blocked = Notifications.promptBlockedReason(app)
        if (blocked != null) {
            container.attempts.resolve(id, AttemptOutcome.BLOCKED, blocked)
        } else {
            val dnd = if (Notifications.doNotDisturbOn(app)) AttemptText.DND_PREFIX else ""
            container.attempts.resolve(
                id, AttemptOutcome.PROMPTED, "${dnd}Countdown ${AppSettings.COUNTDOWN_MS / 1000}s",
            )
        }
    }

    /**
     * The window closed and the glasses were never on. The user was never asked, so the
     * day is logged as NOT_WORN and the streak carries.
     */
    private suspend fun closeUnpromptedDay(container: de.freal.threeseconds.AppContainer) {
        val today = appToday().key()
        val promptedToday = container.settings.current().promptedDay == today
        val existing = container.database.days().forDay(today)

        if (!promptedToday && existing == null) {
            container.database.days().upsert(
                DayLog(day = today, status = DayStatus.NOT_WORN, updatedAt = System.currentTimeMillis())
            )
            Log.i(TAG, "Window closed without catching the glasses on; streak preserved")
        }
        container.scheduler.ensureScheduled(force = true, skipToday = true)
    }

    /**
     * The countdown ran out untouched. This is the one case that breaks a streak -- the
     * prompt arrived and went unanswered. The day stays open in the sense that a manual
     * recording still overwrites it to RECORDED, but there are no further nudges.
     */
    private suspend fun expire(app: Context) {
        val container = app.container
        Notifications.cancel(app, Notifications.ID_PROMPT)

        val today = appToday().key()
        if (container.database.days().forDay(today)?.status == DayStatus.RECORDED) {
            container.attempts.respondToOpenPrompt("Countdown ran out; today was already recorded")
            return
        }

        container.attempts.respondToOpenPrompt("No answer before the countdown ran out; day missed")
        container.database.days().upsert(
            DayLog(day = today, status = DayStatus.MISSED, updatedAt = System.currentTimeMillis())
        )
        Log.i(TAG, "Prompt expired unanswered; streak broken unless recorded manually")
        container.scheduler.ensureScheduled(force = true, skipToday = true)
    }

    companion object {
        const val ACTION_FIRE = "de.freal.threeseconds.action.DAILY_FIRE"
        const val ACTION_PROMPT_EXPIRED = "de.freal.threeseconds.action.PROMPT_EXPIRED"
        const val ACTION_TEST_FIRE = "de.freal.threeseconds.action.TEST_FIRE"
        const val ACTION_TEST_EXPIRED = "de.freal.threeseconds.action.TEST_EXPIRED"
        const val EXTRA_ARMED_FOR = "armed_for"
        private const val TAG = "DailyTrigger"
    }
}

/** Alarms do not survive a reboot or an app update, so re-arm whatever was pending. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val app = context.applicationContext
        goAsyncScope {
            // Not force: a reboot must restore the moment already chosen for today,
            // not roll a new one (which would let a restart dodge the prompt).
            app.container.scheduler.ensureScheduled()
        }
    }
}
