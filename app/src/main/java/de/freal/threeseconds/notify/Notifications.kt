package de.freal.threeseconds.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.freal.threeseconds.R
import de.freal.threeseconds.data.AppSettings
import de.freal.threeseconds.ui.MainActivity

object Notifications {

    /**
     * Channel settings are immutable once created -- Android ignores later changes to
     * importance or vibration on an existing id. The suffix is therefore a version:
     * bump it whenever the alerting behaviour below changes, or existing installs keep
     * the old, quieter channel forever. [LEGACY_PROMPT_CHANNELS] gets cleaned up.
     */
    const val CHANNEL_PROMPT = "daily_prompt_v2"
    const val CHANNEL_STATUS = "recording_status"

    private val LEGACY_PROMPT_CHANNELS = listOf("daily_prompt")

    const val ID_PROMPT = 1001
    const val ID_STATUS = 1002

    /**
     * A long, insistent buzz. You have 60 seconds to answer, so this has to be
     * noticeable through a pocket -- three long pulses rather than a single blip.
     */
    val PROMPT_VIBRATION = longArrayOf(0, 700, 250, 700, 250, 1000)

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        LEGACY_PROMPT_CHANNELS.forEach { manager.deleteNotificationChannel(it) }

        val prompt = NotificationChannel(
            CHANNEL_PROMPT,
            context.getString(R.string.channel_prompt_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_prompt_desc)
            enableVibration(true)
            vibrationPattern = PROMPT_VIBRATION
            enableLights(true)
            lightColor = PROMPT_LIGHT_COLOR
            setShowBadge(true)
            // Only honoured once the user grants Do Not Disturb access; harmless if not,
            // but it means the prompt can still land when they have opted in.
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        // Low importance: the capture status should never buzz the watch, the prompt
        // already did that.
        val status = NotificationChannel(
            CHANNEL_STATUS,
            context.getString(R.string.channel_status_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_status_desc)
            setShowBadge(false)
        }

        manager.createNotificationChannels(listOf(prompt, status))
    }

    /**
     * The daily prompt.
     *
     * The actions are backed by broadcasts rather than activities on purpose: a
     * broadcast [PendingIntent] fired from the watch runs here on the phone without
     * waking the phone UI or asking the user to "open on phone", which is what makes
     * recording straight from the wrist possible. Tapping a notification action is
     * also an exemption from the Android 12+ background foreground-service launch
     * restriction, so the receiver is allowed to start the capture service.
     */
    fun buildPrompt(
        context: Context,
        snoozeCount: Int,
        streak: Int,
        deadlineAtMillis: Long = System.currentTimeMillis() + AppSettings.COUNTDOWN_MS,
        /** A test prompt from the debug screen: same notification, actions marked as a test. */
        test: Boolean = false,
    ): Notification {
        val record = NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            "Record 3s",
            broadcast(context, PromptActionReceiver.ACTION_RECORD, if (test) REQ_TEST_RECORD else REQ_RECORD, test),
        ).build()

        val snoozesLeft = if (test) AppSettings.MAX_SNOOZES else AppSettings.MAX_SNOOZES - snoozeCount
        val snooze = if (snoozesLeft > 0) {
            NotificationCompat.Action.Builder(
                R.drawable.ic_notification,
                "Snooze ${AppSettings.SNOOZE_MINUTES}m",
                broadcast(context, PromptActionReceiver.ACTION_SNOOZE, if (test) REQ_TEST_SNOOZE else REQ_SNOOZE, test),
            ).build()
        } else {
            null
        }

        val openApp = PendingIntent.getActivity(
            context,
            REQ_OPEN,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = buildString {
            if (test) append("Test prompt. ")
            append("Three seconds of right now.")
            if (streak > 0) append("  $streak day streak.")
            if (snoozesLeft in 1 until AppSettings.MAX_SNOOZES) {
                append("  $snoozesLeft snooze left.")
            }
        }

        // Mirror the actions onto the Wear extender so the watch renders them as its
        // own buttons instead of inheriting the phone layout.
        val wearable = NotificationCompat.WearableExtender()
            .addAction(record)
            .apply { snooze?.let { addAction(it) } }
            .setContentIntentAvailableOffline(true)

        return NotificationCompat.Builder(context, CHANNEL_PROMPT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("What's happening?")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVibrate(PROMPT_VIBRATION)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_LIGHTS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            // Alert once per post; a snooze posts a fresh one, which buzzes again.
            .setOnlyAlertOnce(true)
            // A live countdown rather than a static note: the chronometer ticks down on
            // the watch face too, so the urgency is visible without opening anything.
            .setShowWhen(true)
            .setWhen(deadlineAtMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            // Clears itself the moment the countdown runs out.
            .setTimeoutAfter((deadlineAtMillis - System.currentTimeMillis()).coerceAtLeast(1_000L))
            // Must stay false, otherwise the notification never bridges to the watch.
            .setLocalOnly(false)
            .setContentIntent(openApp)
            .addAction(record)
            .apply { snooze?.let { addAction(it) } }
            .extend(wearable)
            .build()
    }

    /** Ongoing notification for the capture foreground service. */
    fun buildStatus(context: Context, text: String, ongoing: Boolean = true): Notification =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Three Seconds")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(ongoing)
            .setLocalOnly(true)
            .build()

    /** A short confirmation that does reach the watch, so the user knows it worked. */
    fun showResult(context: Context, title: String, text: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setTimeoutAfter(RESULT_TIMEOUT_MS)
            .setLocalOnly(false)
            .build()
        notify(context, ID_RESULT, n)
    }

    /**
     * Why the daily prompt would not be shown right now, or null if nothing stands in
     * its way. [notify] skips silently when notifications are off, so this is the only
     * place that reason surfaces.
     */
    fun promptBlockedReason(context: Context): String? {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return "Notifications are turned off for the app"
        }
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(CHANNEL_PROMPT)
            ?: return "The daily prompt channel does not exist"
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) {
            return "The \"${channel.name}\" notification category is turned off"
        }
        return null
    }

    /** Do Not Disturb in any form; the prompt posts but may not buzz or bridge. */
    fun doNotDisturbOn(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).currentInterruptionFilter >
            NotificationManager.INTERRUPTION_FILTER_ALL

    fun notify(context: Context, id: Int, notification: Notification) {
        val manager = NotificationManagerCompat.from(context)
        if (manager.areNotificationsEnabled()) {
            runCatching { manager.notify(id, notification) }
        }
    }

    fun cancel(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    /** Test and real actions need different request codes: PendingIntents ignore extras when matching. */
    private fun broadcast(context: Context, action: String, requestCode: Int, test: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, PromptActionReceiver::class.java)
                .setAction(action)
                .putExtra(PromptActionReceiver.EXTRA_TEST, test),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private const val PROMPT_LIGHT_COLOR = 0xFFFFB74D.toInt()
    private const val ID_RESULT = 1003
    private const val REQ_RECORD = 10
    private const val REQ_SNOOZE = 11
    private const val REQ_OPEN = 12
    private const val REQ_TEST_RECORD = 13
    private const val REQ_TEST_SNOOZE = 14
    private const val RESULT_TIMEOUT_MS = 30_000L
}
