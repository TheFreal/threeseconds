package de.freal.threeseconds.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.freal.threeseconds.container
import de.freal.threeseconds.data.AppSettings
import de.freal.threeseconds.data.DayLog
import de.freal.threeseconds.data.DayStatus
import de.freal.threeseconds.data.computeStreak
import de.freal.threeseconds.data.key
import de.freal.threeseconds.glasses.GlassesManager
import de.freal.threeseconds.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

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
        }
    }

    private suspend fun attempt(app: Context) {
        val container = app.container
        if (!container.settings.current().enabled) return

        val today = LocalDate.now().key()
        if (container.database.days().forDay(today)?.status == DayStatus.RECORDED) {
            Log.i(TAG, "Already recorded today; arming tomorrow")
            container.scheduler.ensureScheduled(force = true, skipToday = true)
            return
        }

        val glasses = GlassesManager.sampleStatus()
        if (!glasses.worn) {
            Log.i(TAG, "Glasses are not on (connected=${glasses.connected}); re-rolling")
            if (!container.scheduler.rerollWithinToday()) closeUnpromptedDay(container)
            return
        }

        prompt(app, container)
    }

    private suspend fun prompt(app: Context, container: de.freal.threeseconds.AppContainer) {
        val settings = container.settings.current()
        val streak = computeStreak(container.database.days().recent(400)).current
        val deadline = System.currentTimeMillis() + AppSettings.COUNTDOWN_MS

        container.settings.setPromptedDay(LocalDate.now().key())
        container.scheduler.armPromptExpiry(deadline)
        Notifications.notify(
            app,
            Notifications.ID_PROMPT,
            Notifications.buildPrompt(app, settings.snoozeCount, streak, deadline),
        )
        Log.i(TAG, "Prompt posted with a ${AppSettings.COUNTDOWN_MS / 1000}s countdown")
    }

    /**
     * The window closed and the glasses were never on. The user was never asked, so the
     * day is logged as NOT_WORN and the streak carries.
     */
    private suspend fun closeUnpromptedDay(container: de.freal.threeseconds.AppContainer) {
        val today = LocalDate.now().key()
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

        val today = LocalDate.now().key()
        if (container.database.days().forDay(today)?.status == DayStatus.RECORDED) return

        container.database.days().upsert(
            DayLog(day = today, status = DayStatus.MISSED, updatedAt = System.currentTimeMillis())
        )
        Log.i(TAG, "Prompt expired unanswered; streak broken unless recorded manually")
        container.scheduler.ensureScheduled(force = true, skipToday = true)
    }

    companion object {
        const val ACTION_FIRE = "de.freal.threeseconds.action.DAILY_FIRE"
        const val ACTION_PROMPT_EXPIRED = "de.freal.threeseconds.action.PROMPT_EXPIRED"
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
