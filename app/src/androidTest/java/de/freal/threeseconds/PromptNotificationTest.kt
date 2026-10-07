package de.freal.threeseconds

import android.app.Notification
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.freal.threeseconds.data.AppSettings
import de.freal.threeseconds.data.DayStatus
import de.freal.threeseconds.data.key
import de.freal.threeseconds.notify.Notifications
import de.freal.threeseconds.notify.PromptActionReceiver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The daily prompt is the entire watch interface, so its shape is worth asserting:
 * the actions must exist, must be broadcasts (an activity action would make the watch
 * say "open on phone" instead of recording), and the snooze must disappear once the
 * budget is spent.
 */
@RunWith(AndroidJUnit4::class)
class PromptNotificationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        Notifications.ensureChannels(context)
    }

    @Test
    fun promptOffersRecordAndSnooze() {
        val n = Notifications.buildPrompt(context, snoozeCount = 0, streak = 4)

        val actions = n.actions
        assertNotNull("Prompt has no actions at all", actions)
        assertEquals("Expected Record and Snooze", 2, actions.size)
        assertTrue(actions[0].title.toString().contains("Record"))
        assertTrue(actions[1].title.toString().contains("Snooze"))
    }

    @Test
    fun actionsAreBroadcastsSoTheWatchCanRunThemOnThePhone() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val n = Notifications.buildPrompt(context, snoozeCount = 0, streak = 0)
        n.actions.forEach { action ->
            assertTrue(
                "${action.title} must be a broadcast PendingIntent",
                action.actionIntent.isBroadcast,
            )
        }
    }

    @Test
    fun theWatchGetsItsOwnCopyOfTheActions() {
        val n = Notifications.buildPrompt(context, snoozeCount = 0, streak = 0)
        val wearActions = NotificationCompat.WearableExtender(n).actions
        assertEquals("Wear extender should mirror both actions", 2, wearActions.size)
    }

    @Test
    fun theNotificationBridgesToTheWatch() {
        val n = Notifications.buildPrompt(context, snoozeCount = 0, streak = 0)
        // localOnly would keep it on the phone and defeat the whole feature.
        assertEquals(0, n.flags and NotificationCompat.FLAG_LOCAL_ONLY)
    }

    @Test
    fun snoozeDisappearsOnceTheBudgetIsSpent() {
        val n = Notifications.buildPrompt(context, snoozeCount = AppSettings.MAX_SNOOZES, streak = 0)
        assertEquals("Only Record should remain", 1, n.actions.size)
        assertTrue(n.actions[0].title.toString().contains("Record"))
    }

    @Test
    fun promptChannelAlertsLoudly() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val channel = manager.getNotificationChannel(Notifications.CHANNEL_PROMPT)

        assertNotNull("Prompt channel missing", channel)
        assertEquals(
            "The prompt must be high importance or it will not heads-up or reach the watch",
            android.app.NotificationManager.IMPORTANCE_HIGH,
            channel!!.importance,
        )
        assertTrue("Vibration disabled on the prompt channel", channel.shouldVibrate())
        assertArrayEquals(
            "Prompt should use the long insistent pattern",
            Notifications.PROMPT_VIBRATION,
            channel.vibrationPattern,
        )
        assertTrue(
            "A 60 second countdown needs a long buzz; total was only ${channel.vibrationPattern!!.sum()}ms",
            channel.vibrationPattern!!.sum() >= 2_000L,
        )
    }

    @Test
    fun statusChannelStaysQuiet() {
        // The capture status must never buzz -- the prompt already did.
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val channel = manager.getNotificationChannel(Notifications.CHANNEL_STATUS)
        assertNotNull(channel)
        assertTrue(channel!!.importance <= android.app.NotificationManager.IMPORTANCE_LOW)
    }

    @Test
    fun promptIsBuiltAtMaxPriority() {
        val n = Notifications.buildPrompt(context, snoozeCount = 0, streak = 0)
        assertEquals(Notification.PRIORITY_MAX, n.priority)
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
    }

    @Test
    fun promptCountsDownAndClearsItself() {
        val deadline = System.currentTimeMillis() + AppSettings.COUNTDOWN_MS
        val n = Notifications.buildPrompt(context, snoozeCount = 0, streak = 0, deadlineAtMillis = deadline)

        assertEquals(
            "The chronometer must point at the deadline so the watch can tick it down",
            deadline,
            n.`when`,
        )
        assertTrue("Chronometer not enabled", n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(
            "Chronometer must count down, not up",
            n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
        )
        assertEquals("Should expire with the countdown", 60_000L, AppSettings.COUNTDOWN_MS)
    }

    @Test
    fun snoozeIsAShortFixedPushBack() {
        // A fixed delay, not a fresh random draw, and short enough to stay urgent.
        assertEquals(5L, AppSettings.SNOOZE_MINUTES)
        assertEquals(2, AppSettings.MAX_SNOOZES)
    }

    @Test
    fun promptActuallyReachesTheShade() = runBlocking {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        Notifications.notify(
            context,
            Notifications.ID_PROMPT,
            Notifications.buildPrompt(context, snoozeCount = 0, streak = 2),
        )
        try {
            // Posting goes through NotificationManagerService asynchronously, so poll
            // rather than reading once and racing the enqueue.
            val posted = withTimeoutOrNull(5_000) {
                var found = manager.activeNotifications.firstOrNull { it.id == Notifications.ID_PROMPT }
                while (found == null) {
                    delay(100)
                    found = manager.activeNotifications.firstOrNull { it.id == Notifications.ID_PROMPT }
                }
                found
            }

            assertNotNull("The prompt never reached the notification shade", posted)
            assertEquals(Notifications.CHANNEL_PROMPT, posted!!.notification.channelId)
            assertEquals(2, posted.notification.actions.size)
        } finally {
            Notifications.cancel(context, Notifications.ID_PROMPT)
        }
    }

    @Test
    fun snoozeActionAdvancesTheBudgetAndLogsTheDay() = runBlocking {
        val container = context.container
        container.settings.setSnoozeCount(0)

        context.sendBroadcast(
            Intent(context, PromptActionReceiver::class.java)
                .setAction(PromptActionReceiver.ACTION_SNOOZE)
        )

        val count = withTimeoutOrNull(15_000) {
            while (container.settings.current().snoozeCount != 1) delay(250)
            container.settings.current().snoozeCount
        }
        assertEquals("Snooze should have been counted", 1, count)

        val day = container.database.days().forDay(LocalDate.now().key())
        assertEquals(DayStatus.SNOOZED, day?.status)

        container.settings.setSnoozeCount(0)
    }
}
