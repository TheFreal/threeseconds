package de.freal.threeseconds

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.freal.threeseconds.data.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The attempt cadence is the heart of the trigger, so it gets pinned down here: the
 * first draw must land near the front of the window, and each re-roll must step forward
 * by a bounded amount rather than subdividing whatever is left.
 */
@RunWith(AndroidJUnit4::class)
class SchedulerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container by lazy { context.container }

    /** Minutes from midnight for "a minute from now", so the window opens in the future. */
    private val nowMinute: Int get() = LocalTime.now().toSecondOfDay() / 60

    private fun midnightMs(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun minuteToMs(minute: Int): Long = midnightMs() + minute * 60_000L

    /** A window that starts shortly from now and leaves plenty of room, whenever the suite runs. */
    private fun openWindow(lengthMinutes: Int = 600): Pair<Int, Int> {
        val start = (nowMinute + 1).coerceAtMost(24 * 60 - 2)
        val end = (start + lengthMinutes).coerceAtMost(24 * 60 - 1)
        return start to end
    }

    @Before
    fun setUp() = runBlocking {
        container.settings.setEnabled(true)
        container.settings.setAttemptCadence(firstSpreadMinutes = 120, retryStepMinutes = 60)
        container.settings.setSchedule("", 0L)
    }

    @Test
    fun firstAttemptLandsNearTheFrontOfTheWindowNotAnywhereInIt() = runBlocking {
        val (start, end) = openWindow()
        container.settings.setWindow(start, end)
        container.scheduler.ensureScheduled(force = true)

        val settings = container.settings.current()
        val at = settings.scheduledAtMillis
        val windowStart = minuteToMs(start)
        val windowLength = minuteToMs(end) - windowStart
        val spread = minOf(settings.firstAttemptSpreadMinutes * 60_000L, windowLength)

        assertTrue("First attempt must be inside the window", at >= windowStart)
        assertTrue(
            "First attempt must sit in the opening stretch, not anywhere in the day. " +
                "Landed ${(at - windowStart) / 60_000} minutes in, limit ${spread / 60_000}",
            at <= windowStart + spread,
        )
    }

    @Test
    fun eachRerollStepsForwardByAtMostTheConfiguredStep() = runBlocking {
        val (start, end) = openWindow()
        container.settings.setWindow(start, end)
        container.scheduler.ensureScheduled(force = true)

        var previous = container.settings.current().scheduledAtMillis
        repeat(3) { round ->
            assertTrue("Should still have room to re-roll", container.scheduler.rerollWithinToday())
            val settings = container.settings.current()
            val step = settings.retryStepMinutes * 60_000L

            assertTrue("Re-roll $round went backwards", settings.scheduledAtMillis > previous)
            assertTrue(
                "Re-roll $round jumped ${(settings.scheduledAtMillis - previous) / 60_000} minutes " +
                    "past the previous attempt, beyond the ${step / 60_000} minute step",
                settings.scheduledAtMillis <= previous + step,
            )
            previous = settings.scheduledAtMillis
        }
    }

    @Test
    fun aLateFirstDrawDoesNotCramRetriesIntoTheRemainder() = runBlocking {
        // The old behaviour drew uniformly across the window, so a late first draw left
        // every retry squeezed into the tail. Attempts should stay step-sized instead.
        val (start, end) = openWindow(lengthMinutes = 600)
        container.settings.setWindow(start, end)
        container.scheduler.ensureScheduled(force = true)

        val step = container.settings.current().retryStepMinutes * 60_000L
        var gaps = 0
        var previous = container.settings.current().scheduledAtMillis

        while (container.scheduler.rerollWithinToday() && gaps < 5) {
            val at = container.settings.current().scheduledAtMillis
            assertTrue(
                "Gap ${at - previous}ms exceeded the ${step}ms step",
                at - previous <= step + 1_000L,
            )
            previous = at
            gaps++
        }
        assertTrue("Expected several bounded re-rolls, got $gaps", gaps >= 3)
    }

    @Test
    fun rerollStopsWhenTheWindowCloses() = runBlocking {
        // A window with only a couple of minutes left has no room for another attempt.
        val start = (nowMinute - 30).coerceAtLeast(0)
        val end = (nowMinute + 1).coerceAtMost(24 * 60 - 1)
        container.settings.setWindow(start, end)
        container.settings.setAttemptCount(0)

        assertFalse(
            "A closed window must end the day rather than keep re-rolling",
            container.scheduler.rerollWithinToday(),
        )
    }

    @Test
    fun snoozeMovesThePromptWithoutSpendingAnAttempt() = runBlocking {
        val (start, end) = openWindow()
        container.settings.setWindow(start, end)
        container.scheduler.ensureScheduled(force = true)
        val before = container.settings.current()

        container.scheduler.scheduleSnooze(AppSettings.SNOOZE_MINUTES)
        val after = container.settings.current()

        assertEquals("Snoozing is not an attempt", before.attemptCount, after.attemptCount)
        val expected = System.currentTimeMillis() + AppSettings.SNOOZE_MINUTES * 60_000L
        assertTrue(
            "Expected a ${AppSettings.SNOOZE_MINUTES} minute push-back, got " +
                "${(after.scheduledAtMillis - System.currentTimeMillis()) / 1000}s",
            kotlin.math.abs(after.scheduledAtMillis - expected) < 10_000L,
        )
    }

    @Test
    fun anAlreadyArmedFutureMomentIsNotRerolled() = runBlocking {
        val (start, end) = openWindow()
        container.settings.setWindow(start, end)
        container.scheduler.ensureScheduled(force = true)
        val first = container.settings.current().scheduledAtMillis

        container.scheduler.ensureScheduled()
        container.scheduler.ensureScheduled()

        assertEquals(
            "Opening the app must not move the prompt",
            first,
            container.settings.current().scheduledAtMillis,
        )
    }

    @Test
    fun cadenceFollowsTheWindowRatherThanFixedClockHours() = runBlocking {
        // Changing the window must change where attempts land, with nothing hardcoded.
        val start = (nowMinute + 1).coerceAtMost(24 * 60 - 2)
        val shortEnd = (start + 90).coerceAtMost(24 * 60 - 1)
        container.settings.setWindow(start, shortEnd)
        container.scheduler.ensureScheduled(force = true)

        val at = container.settings.current().scheduledAtMillis
        assertTrue("Must stay inside the shortened window", at in minuteToMs(start)..minuteToMs(shortEnd))
    }
}
