package de.freal.threeseconds

import android.app.NotificationManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import de.freal.threeseconds.notify.Notifications
import de.freal.threeseconds.schedule.DailyTriggerReceiver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The trigger firing while the glasses are off must re-roll and get out of the way --
 * no prompt, no lingering service. This is the end-to-end version of that rule.
 */
@RunWith(AndroidJUnit4::class)
class TriggerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container by lazy { context.container }

    @Before
    fun setUp() = runBlocking {
        // No glasses at all: the sample must come back "not worn".
        MockDeviceKit.getInstance(context).disable()
        container.settings.setEnabled(true)
        container.settings.setWindow(0, 24 * 60 - 1)
        container.scheduler.ensureScheduled(force = true)
        Notifications.cancel(context, Notifications.ID_PROMPT)
    }

    @After
    fun tearDown() {
        Notifications.cancel(context, Notifications.ID_PROMPT)
    }

    @Test
    fun firingWithoutGlassesRerollsAndStaysQuiet() = runBlocking {
        val before = container.settings.current()
        assertEquals(0, before.attemptCount)

        context.sendBroadcast(
            Intent(context, DailyTriggerReceiver::class.java).setAction(DailyTriggerReceiver.ACTION_FIRE)
        )

        val after = withTimeoutOrNull(25_000) {
            while (container.settings.current().attemptCount == 0) delay(250)
            container.settings.current()
        }

        assertTrue("The trigger never re-rolled", after != null)
        assertEquals("Exactly one attempt should have been spent", 1, after!!.attemptCount)
        assertTrue(
            "The re-rolled moment must be in the future",
            after.scheduledAtMillis > System.currentTimeMillis(),
        )
        assertTrue(
            "Re-roll should move the prompt",
            after.scheduledAtMillis != before.scheduledAtMillis,
        )

        // Nothing should have been shown: the glasses were off.
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNull(
            "A prompt was posted even though the glasses were off",
            manager.activeNotifications.firstOrNull { it.id == Notifications.ID_PROMPT },
        )
    }
}
