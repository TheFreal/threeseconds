package de.freal.threeseconds

import de.freal.threeseconds.data.DayLog
import de.freal.threeseconds.data.DayStatus
import de.freal.threeseconds.data.computeStreak
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StreakTest {

    private val today = LocalDate.of(2026, 10, 6)

    private fun log(daysAgo: Int, status: DayStatus) =
        DayLog(today.minusDays(daysAgo.toLong()).toString(), status, 0L)

    @Test
    fun countsConsecutiveRecordedDays() {
        val logs = (0..4).map { log(it, DayStatus.RECORDED) }
        val streak = computeStreak(logs, today)
        assertEquals(5, streak.current)
        assertTrue(streak.recordedToday)
    }

    @Test
    fun todayStillOpenDoesNotBreakTheStreak() {
        // Recorded yesterday and before, nothing yet today.
        val logs = (1..3).map { log(it, DayStatus.RECORDED) }
        val streak = computeStreak(logs, today)
        assertEquals(3, streak.current)
        assertFalse(streak.recordedToday)
    }

    @Test
    fun aFailedAttemptKeepsTheStreakAlive() {
        // The user tapped Record, the glasses dropped. That should not cost the streak.
        val logs = listOf(
            log(0, DayStatus.ATTEMPTED),
            log(1, DayStatus.RECORDED),
            log(2, DayStatus.RECORDED),
        )
        val streak = computeStreak(logs, today)
        assertEquals(3, streak.current)
        assertFalse("An attempt is not a recording", streak.recordedToday)
    }

    @Test
    fun aDayWithoutTheGlassesKeepsTheStreak() {
        // Never wore them, so never got asked. There was nothing to miss.
        val logs = listOf(
            log(1, DayStatus.NOT_WORN),
            log(2, DayStatus.RECORDED),
            log(3, DayStatus.RECORDED),
        )
        assertEquals(3, computeStreak(logs, today).current)
    }

    @Test
    fun onlyIgnoringThePromptBreaksTheStreak() {
        // Prompted yesterday, countdown ran out untouched.
        val logs = listOf(
            log(1, DayStatus.MISSED),
            log(2, DayStatus.RECORDED),
            log(3, DayStatus.RECORDED),
        )
        assertEquals(0, computeStreak(logs, today).current)
    }

    @Test
    fun aLongUnwornStretchStillCarriesTheStreak() {
        val logs = listOf(
            log(0, DayStatus.RECORDED),
            log(1, DayStatus.NOT_WORN),
            log(2, DayStatus.NOT_WORN),
            log(3, DayStatus.NOT_WORN),
            log(4, DayStatus.RECORDED),
        )
        assertEquals(5, computeStreak(logs, today).current)
    }

    @Test
    fun anUnresolvedSnoozeDoesNotCount() {
        val logs = listOf(
            log(1, DayStatus.SNOOZED),
            log(2, DayStatus.RECORDED),
        )
        assertEquals(0, computeStreak(logs, today).current)
    }

    @Test
    fun aGapResetsTheCurrentStreakButNotTheBest() {
        val logs = listOf(
            log(0, DayStatus.RECORDED),
            // gap at 1 day ago
            log(2, DayStatus.RECORDED),
            log(3, DayStatus.RECORDED),
            log(4, DayStatus.RECORDED),
        )
        val streak = computeStreak(logs, today)
        assertEquals(1, streak.current)
        assertEquals(3, streak.longest)
    }

    @Test
    fun emptyHistoryIsZero() {
        val streak = computeStreak(emptyList(), today)
        assertEquals(0, streak.current)
        assertEquals(0, streak.longest)
        assertFalse(streak.recordedToday)
    }
}
