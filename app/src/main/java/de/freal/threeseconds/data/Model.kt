package de.freal.threeseconds.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** A single recorded clip, stored as a row pointing at the MediaStore entry. */
@Entity(tableName = "clips")
data class Clip(
    @PrimaryKey val id: String,
    /** MediaStore content URI for the .mp4 in Movies/ThreeSeconds. */
    val uri: String,
    val recordedAt: Long,
    /** ISO yyyy-MM-dd of the local day this clip counts for. */
    val day: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val deviceName: String?,
) {
    val month: String get() = day.substring(0, 7)
}

/**
 * How a given day ended up. One row per day.
 *
 * [DayStatus.ATTEMPTED] exists because the user chose "streak preserved on failure":
 * reaching for the glasses and having Bluetooth drop should not cost a streak.
 */
@Entity(tableName = "day_log")
data class DayLog(
    @PrimaryKey val day: String,
    val status: DayStatus,
    val updatedAt: Long,
)

enum class DayStatus {
    /** A clip was captured and written. */
    RECORDED,

    /** The user tapped Record but the glasses could not deliver. Does not break the streak. */
    ATTEMPTED,

    /**
     * The window closed without ever catching the glasses on. You were never asked, so
     * there was nothing to miss -- the streak survives.
     */
    NOT_WORN,

    /** Prompted, snoozed, and the day has not resolved yet. */
    SNOOZED,

    /**
     * Prompted, and the countdown ran out without a record or a snooze. This is the
     * only way to lose a streak: you were asked and did not answer.
     */
    MISSED,
}

/**
 * Statuses that keep a streak alive.
 *
 * The rule is about being asked, not about recording: a day spent without the glasses
 * never produced a prompt, so it cannot be a miss.
 */
val DayStatus.countsForStreak: Boolean
    get() = when (this) {
        DayStatus.RECORDED, DayStatus.ATTEMPTED, DayStatus.NOT_WORN -> true
        DayStatus.SNOOZED, DayStatus.MISSED -> false
    }

class Converters {
    @TypeConverter fun toStatus(value: String): DayStatus = DayStatus.valueOf(value)
    @TypeConverter fun fromStatus(value: DayStatus): String = value.name
    @TypeConverter fun toKind(value: String): AttemptKind = AttemptKind.valueOf(value)
    @TypeConverter fun fromKind(value: AttemptKind): String = value.name
    @TypeConverter fun toOutcome(value: String): AttemptOutcome = AttemptOutcome.valueOf(value)
    @TypeConverter fun fromOutcome(value: AttemptOutcome): String = value.name
}

fun LocalDate.key(): String = toString()

/**
 * 3S days run from 05:00 to 05:00 rather than midnight to midnight, so the window can
 * stretch past midnight and a prompt or clip at 1am still counts for the evening before.
 */
const val DAY_START_MINUTE = 5 * 60
const val DAY_END_MINUTE = DAY_START_MINUTE + 24 * 60

/** The 3S day [time] belongs to. Wall-clock arithmetic, so DST nights don't shift it. */
fun appDayOf(time: LocalDateTime): LocalDate = time.minusMinutes(DAY_START_MINUTE.toLong()).toLocalDate()

fun appDayOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    appDayOf(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone))

fun appToday(zone: ZoneId = ZoneId.systemDefault()): LocalDate = appDayOf(LocalDateTime.now(zone))

/** The instant [minute] minutes of wall-clock time after this date's midnight; may run into the next date. */
fun LocalDate.atMinute(minute: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
    atStartOfDay().plusMinutes(minute.toLong()).atZone(zone).toInstant().toEpochMilli()
