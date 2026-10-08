package de.freal.threeseconds.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How a day is drawn on the streak calendar. */
enum class DayMark {
    /** At least one clip exists for the day. */
    CLIP,

    /** No clip, but the day kept the streak (the glasses failed, or were never on). */
    KEPT,

    /** Asked and not answered, or snoozed into the night. Breaks the streak. */
    MISSED,

    /** Today, not decided yet. */
    OPEN,

    /** A past day with nothing logged. */
    NONE,

    FUTURE,
}

val DayMark.inStreak: Boolean get() = this == DayMark.CLIP || this == DayMark.KEPT

fun dayMark(date: LocalDate, today: LocalDate, log: DayLog?, hasClip: Boolean): DayMark = when {
    date.isAfter(today) -> DayMark.FUTURE
    hasClip -> DayMark.CLIP
    else -> when (log?.status) {
        DayStatus.RECORDED, DayStatus.ATTEMPTED, DayStatus.NOT_WORN -> DayMark.KEPT
        DayStatus.MISSED -> DayMark.MISSED
        DayStatus.SNOOZED -> if (date == today) DayMark.OPEN else DayMark.MISSED
        null -> if (date == today) DayMark.OPEN else DayMark.NONE
    }
}

enum class StreakEffect { KEPT, BROKEN, OPEN, NONE }

/** A plain-language account of a day without a clip, for the calendar's day sheet. */
data class DayStory(val title: String, val body: String, val effect: StreakEffect)

/** Everything known about one day. */
data class DayFacts(
    val date: LocalDate,
    val today: LocalDate,
    val log: DayLog?,
    val clips: List<Clip>,
    /** Prompt alarms scheduled for this day, oldest first. */
    val attempts: List<Attempt>,
    /** The day the app was installed, if known. */
    val trackingSince: LocalDate?,
    val promptsEnabled: Boolean,
    /** The next armed prompt, if any. Only used when [date] is today. */
    val nextPromptAt: Long?,
    val zone: ZoneId = ZoneId.systemDefault(),
)

/**
 * Explains a day in words a user can act on, from the day log and the attempt log.
 *
 * The attempt log only goes back to when it was introduced, so older days fall back to
 * what the day log alone can say.
 */
fun describeDay(f: DayFacts): DayStory {
    val clock = { millis: Long -> CLOCK.format(Instant.ofEpochMilli(millis).atZone(f.zone)) }
    val a = f.attempts

    if (f.clips.isNotEmpty()) {
        val times = f.clips.joinToString(", ") { clock(it.recordedAt) }
        return DayStory("Recorded at $times", "", StreakEffect.KEPT)
    }

    val notWorn = a.filter { it.outcome == AttemptOutcome.NOT_WORN }
    val prompt = a.lastOrNull { it.outcome == AttemptOutcome.PROMPTED || it.outcome == AttemptOutcome.BLOCKED }
    val neverFired = a.filter { it.outcome == AttemptOutcome.NEVER_FIRED }
    val snoozes = a.count { it.response?.startsWith(AttemptText.SNOOZED_PREFIX) == true }

    when (f.log?.status) {
        DayStatus.RECORDED -> return DayStory(
            "The clip is gone",
            "A clip was recorded this day, but it is no longer in your library. It may have been " +
                "deleted from the gallery.",
            StreakEffect.KEPT,
        )

        DayStatus.ATTEMPTED -> {
            val failed = a.lastOrNull { it.response?.startsWith(AttemptText.CAPTURE_FAILED_PREFIX) == true }
            val at = failed?.firedAt?.let { " at ${clock(it)}" } ?: ""
            val reason = failed?.response?.removePrefix(AttemptText.CAPTURE_FAILED_PREFIX)
                ?.let { ": ${sentence(it)}" } ?: "."
            return DayStory(
                "The glasses didn't come through",
                "You tapped Record$at, but the recording failed$reason " +
                    "A failed recording still counts, so your streak is safe.",
                StreakEffect.KEPT,
            )
        }

        DayStatus.NOT_WORN -> return DayStory(
            "Your glasses weren't on",
            notWornSentence(notWorn, clock) + " You were never asked, so your streak is safe.",
            StreakEffect.KEPT,
        )

        DayStatus.MISSED -> {
            if (prompt?.outcome == AttemptOutcome.BLOCKED) {
                val at = prompt.firedAt?.let { "At ${clock(it)} your" } ?: "Your"
                return DayStory(
                    "The prompt couldn't be shown",
                    "$at glasses were on, but ${blockedCause(prompt.detail)}, so the prompt never " +
                        "appeared. The minute ran out and the day counted as missed, which ended your streak.",
                    StreakEffect.BROKEN,
                )
            }
            val at = prompt?.firedAt?.let { " at ${clock(it)}" } ?: ""
            val snoozed = when (snoozes) {
                0 -> ""
                1 -> " You had snoozed it once before."
                else -> " You had snoozed it $snoozes times before."
            }
            val dnd = if (prompt?.detail?.startsWith(AttemptText.DND_PREFIX) == true) {
                " Do Not Disturb was on, so it may have arrived without a buzz."
            } else {
                ""
            }
            return DayStory(
                "The prompt went unanswered",
                "The prompt arrived$at and the minute ran out without a recording.$snoozed$dnd " +
                    "This ended your streak.",
                StreakEffect.BROKEN,
            )
        }

        DayStatus.SNOOZED -> {
            if (f.date == f.today) return openToday(f, notWorn, clock)
            val lost = if (neverFired.isNotEmpty()) {
                " The follow-up prompt never arrived because the phone didn't wake 3S up for it."
            } else {
                ""
            }
            return DayStory(
                "Snoozed, then never recorded",
                "You snoozed the prompt and the day ended without a recording.$lost This ended your streak.",
                StreakEffect.BROKEN,
            )
        }

        null -> Unit
    }

    if (f.date == f.today) return openToday(f, notWorn, clock)

    if (f.trackingSince != null && f.date.isBefore(f.trackingSince)) {
        return DayStory("Before 3S", "3S wasn't installed yet.", StreakEffect.NONE)
    }

    val noResult = "With nothing logged, this day doesn't count toward your streak."
    return when {
        neverFired.isNotEmpty() -> DayStory(
            "The check never happened",
            "3S planned to check for your glasses at ${clock(neverFired.first().scheduledAt)}, but the " +
                "phone never woke the app up for it. That happens when the phone is off, the app was " +
                "force-stopped, or battery saving held it back. $noResult",
            StreakEffect.BROKEN,
        )

        a.any { it.outcome == AttemptOutcome.DISABLED } -> DayStory(
            "Daily prompts were off",
            "The daily prompt was switched off in Settings, so 3S didn't ask. $noResult",
            StreakEffect.BROKEN,
        )

        a.any { it.outcome == AttemptOutcome.FAILED || it.outcome == AttemptOutcome.FIRING } -> DayStory(
            "Something went wrong",
            "3S ran into an error while checking for your glasses. $noResult",
            StreakEffect.BROKEN,
        )

        // Re-rolls were logged but the day never closed, e.g. the last one was lost.
        notWorn.isNotEmpty() -> DayStory(
            "Your glasses weren't on",
            notWornSentence(notWorn, clock) + " The day was never closed off, so it " +
                "doesn't count toward your streak.",
            StreakEffect.BROKEN,
        )

        else -> DayStory(
            "No record of this day",
            "3S has no record of checking for your glasses this day. The phone may have been off, " +
                "the app may have been force-stopped, or daily prompts were switched off. $noResult",
            StreakEffect.BROKEN,
        )
    }
}

private fun openToday(f: DayFacts, notWorn: List<Attempt>, clock: (Long) -> String): DayStory {
    val checked = if (notWorn.isNotEmpty()) " " + notWornSentence(notWorn, clock) else ""
    val next = f.nextPromptAt
    val body = when {
        !f.promptsEnabled -> "Daily prompts are switched off in Settings."
        next != null && appDayOf(next, f.zone) == f.today ->
            "The next check is at ${clock(next)}. If your glasses are on then, your watch will buzz.$checked"
        else -> "There are no more checks planned for today.$checked"
    }
    return DayStory("Today is still open", body.trim(), StreakEffect.OPEN)
}

private fun notWornSentence(notWorn: List<Attempt>, clock: (Long) -> String): String {
    if (notWorn.isEmpty()) return "3S checked through the day, but your glasses were never on."
    val times = notWorn.mapNotNull { it.firedAt ?: it.scheduledAt }
    val span = if (times.size == 1) {
        "once, at ${clock(times.first())}"
    } else {
        "${times.size} times between ${clock(times.min())} and ${clock(times.max())}"
    }
    val neverConnected = notWorn.all { it.detail?.startsWith(AttemptText.NOT_CONNECTED) == true }
    val state = if (neverConnected) "weren't connected to your phone" else "weren't on"
    return "3S checked $span, and your glasses $state."
}

private fun blockedCause(detail: String?): String = when {
    detail == null -> "Android wouldn't show the notification"
    detail.contains("for the app") -> "notifications for 3S were turned off"
    detail.contains("category") -> "the Daily prompt notification category was turned off"
    else -> "Android wouldn't show the notification"
}

/** Lower-cases the first letter and makes sure the reason ends a sentence. */
private fun sentence(reason: String): String {
    val trimmed = reason.trim().trimEnd('.')
    val lowered = trimmed.replaceFirstChar { it.lowercase() }
    return "$lowered."
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
