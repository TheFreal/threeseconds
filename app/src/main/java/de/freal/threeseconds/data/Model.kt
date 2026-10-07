package de.freal.threeseconds.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import java.time.LocalDate

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
}

fun LocalDate.key(): String = toString()
