package de.freal.threeseconds

import de.freal.threeseconds.data.Attempt
import de.freal.threeseconds.data.AttemptKind
import de.freal.threeseconds.data.AttemptOutcome
import de.freal.threeseconds.data.AttemptText
import de.freal.threeseconds.data.Clip
import de.freal.threeseconds.data.DayFacts
import de.freal.threeseconds.data.DayLog
import de.freal.threeseconds.data.DayMark
import de.freal.threeseconds.data.DayStatus
import de.freal.threeseconds.data.StreakEffect
import de.freal.threeseconds.data.dayMark
import de.freal.threeseconds.data.describeDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class DayStoryTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 8)
    private val day = LocalDate.of(2026, 10, 5)

    private fun at(hour: Int, minute: Int = 0, date: LocalDate = day) =
        date.atTime(hour, minute).toInstant(zone).toEpochMilli()

    private fun log(status: DayStatus, date: LocalDate = day) = DayLog(date.toString(), status, 0L)

    private fun attempt(
        hour: Int,
        outcome: AttemptOutcome,
        detail: String? = null,
        response: String? = null,
        kind: AttemptKind = AttemptKind.RETRY,
        fired: Boolean = true,
    ) = Attempt(
        kind = kind,
        scheduledAt = at(hour),
        createdAt = 0L,
        firedAt = if (fired) at(hour, 1) else null,
        outcome = outcome,
        detail = detail,
        response = response,
    )

    private fun facts(
        log: DayLog? = null,
        attempts: List<Attempt> = emptyList(),
        clips: List<Clip> = emptyList(),
        date: LocalDate = day,
        nextPromptAt: Long? = null,
    ) = DayFacts(
        date = date,
        today = today,
        log = log,
        clips = clips,
        attempts = attempts,
        trackingSince = LocalDate.of(2026, 9, 1),
        promptsEnabled = true,
        nextPromptAt = nextPromptAt,
        zone = zone,
    )

    @Test
    fun marksDays() {
        assertEquals(DayMark.CLIP, dayMark(day, today, null, hasClip = true))
        assertEquals(DayMark.KEPT, dayMark(day, today, log(DayStatus.NOT_WORN), hasClip = false))
        assertEquals(DayMark.KEPT, dayMark(day, today, log(DayStatus.ATTEMPTED), hasClip = false))
        assertEquals(DayMark.MISSED, dayMark(day, today, log(DayStatus.SNOOZED), hasClip = false))
        assertEquals(DayMark.OPEN, dayMark(today, today, log(DayStatus.SNOOZED, today), hasClip = false))
        assertEquals(DayMark.NONE, dayMark(day, today, null, hasClip = false))
        assertEquals(DayMark.FUTURE, dayMark(today.plusDays(1), today, null, hasClip = false))
    }

    @Test
    fun notWornListsTheChecks() {
        val story = describeDay(
            facts(
                log(DayStatus.NOT_WORN),
                listOf(
                    attempt(9, AttemptOutcome.NOT_WORN, AttemptText.NOT_CONNECTED, kind = AttemptKind.FIRST),
                    attempt(10, AttemptOutcome.NOT_WORN, AttemptText.NOT_WORN),
                    attempt(20, AttemptOutcome.NOT_WORN, AttemptText.NOT_CONNECTED),
                ),
            )
        )
        assertEquals(StreakEffect.KEPT, story.effect)
        assertEquals(
            "3S checked 3 times between 09:01 and 20:01, and your glasses weren't on. " +
                "You were never asked, so your streak is safe.",
            story.body,
        )
    }

    @Test
    fun failedCaptureQuotesTheReason() {
        val story = describeDay(
            facts(
                log(DayStatus.ATTEMPTED),
                listOf(
                    attempt(
                        14, AttemptOutcome.PROMPTED,
                        response = AttemptText.CAPTURE_FAILED_PREFIX + "The glasses did not come online in time",
                    )
                ),
            )
        )
        assertEquals(
            "You tapped Record at 14:01, but the recording failed: the glasses did not come online " +
                "in time. A failed recording still counts, so your streak is safe.",
            story.body,
        )
    }

    @Test
    fun missedPromptMentionsSnoozesAndDnd() {
        val story = describeDay(
            facts(
                log(DayStatus.MISSED),
                listOf(
                    attempt(12, AttemptOutcome.PROMPTED, "Countdown 60s", AttemptText.SNOOZED_PREFIX + "5m (1/2)"),
                    attempt(12, AttemptOutcome.PROMPTED, AttemptText.DND_PREFIX + "Countdown 60s", "No answer"),
                ),
            )
        )
        assertEquals(StreakEffect.BROKEN, story.effect)
        assertTrue(story.body, story.body.startsWith("The prompt arrived at 12:01"))
        assertTrue(story.body, story.body.contains("snoozed it once"))
        assertTrue(story.body, story.body.contains("Do Not Disturb"))
    }

    @Test
    fun blockedPromptSaysWhy() {
        val story = describeDay(
            facts(
                log(DayStatus.MISSED),
                listOf(attempt(15, AttemptOutcome.BLOCKED, "Notifications are turned off for the app")),
            )
        )
        assertEquals("The prompt couldn't be shown", story.title)
        assertTrue(story.body, story.body.contains("notifications for 3S were turned off"))
    }

    @Test
    fun nothingLoggedPrefersNeverFired() {
        val story = describeDay(facts(attempts = listOf(attempt(11, AttemptOutcome.NEVER_FIRED, fired = false))))
        assertEquals("The check never happened", story.title)
        assertTrue(story.body, story.body.contains("11:00"))
    }

    @Test
    fun beforeInstallIsNotABreak() {
        val story = describeDay(facts(date = LocalDate.of(2026, 8, 20)))
        assertEquals(StreakEffect.NONE, story.effect)
    }

    @Test
    fun todayNamesTheNextCheck() {
        val story = describeDay(
            facts(
                date = today,
                attempts = listOf(attempt(9, AttemptOutcome.NOT_WORN, AttemptText.NOT_WORN)),
                nextPromptAt = at(16, 30, today),
            )
        )
        assertEquals(StreakEffect.OPEN, story.effect)
        assertTrue(story.body, story.body.startsWith("The next check is at 16:30."))
    }
}
