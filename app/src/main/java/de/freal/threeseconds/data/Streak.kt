package de.freal.threeseconds.data

import java.time.LocalDate

data class StreakInfo(
    val current: Int,
    val longest: Int,
    /** True once today already has a clip, so the UI can stop nagging. */
    val recordedToday: Boolean,
)

/**
 * Walks backwards from today counting unbroken days.
 *
 * Today not yet being recorded does not break the streak -- the day is still in
 * progress -- so the walk starts at yesterday when today is empty.
 */
fun computeStreak(logs: List<DayLog>, today: LocalDate = LocalDate.now()): StreakInfo {
    val byDay = logs.associateBy { it.day }
    val recordedToday = byDay[today.key()]?.status == DayStatus.RECORDED

    var cursor = if (byDay[today.key()]?.status?.countsForStreak == true) today else today.minusDays(1)
    var current = 0
    while (byDay[cursor.key()]?.status?.countsForStreak == true) {
        current++
        cursor = cursor.minusDays(1)
    }

    // Longest run anywhere in the log.
    val days = logs.filter { it.status.countsForStreak }
        .map { LocalDate.parse(it.day) }
        .sorted()
    var longest = 0
    var run = 0
    var prev: LocalDate? = null
    for (d in days) {
        run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
        longest = maxOf(longest, run)
        prev = d
    }

    return StreakInfo(current = current, longest = maxOf(longest, current), recordedToday = recordedToday)
}
