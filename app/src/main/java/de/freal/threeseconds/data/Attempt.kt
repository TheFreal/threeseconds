package de.freal.threeseconds.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * One armed prompt alarm and what became of it. Kept for the debug screen only; nothing
 * in the scheduling logic reads it back.
 */
@Entity(tableName = "attempts")
data class Attempt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: AttemptKind,
    /** When the alarm was armed for. */
    val scheduledAt: Long,
    /** When the alarm was armed. */
    val createdAt: Long,
    /** When the alarm actually went off, if it did. */
    val firedAt: Long? = null,
    val outcome: AttemptOutcome = AttemptOutcome.PENDING,
    /** Why the outcome is what it is, in words. */
    val detail: String? = null,
    /** What the user did with a prompt that was shown. */
    val response: String? = null,
)

enum class AttemptKind(val label: String) {
    FIRST("First attempt"),
    RETRY("Retry"),
    SNOOZE("Snooze"),
}

enum class AttemptOutcome(val label: String) {
    /** Armed and not yet due. */
    PENDING("Scheduled"),

    /** The alarm went off but the receiver never recorded a result (crash or kill). */
    FIRING("Fired, no result"),

    PROMPTED("Prompt shown"),

    /** The alarm went off, but the system would not have shown the notification. */
    BLOCKED("Prompt blocked"),

    NOT_WORN("Glasses not on"),
    ALREADY_RECORDED("Already recorded"),
    DISABLED("Prompts turned off"),

    /** A later alarm was armed after this one's time had passed without it going off. */
    NEVER_FIRED("Alarm never fired"),

    FAILED("Error"),
}

@Dao
interface AttemptDao {
    @Insert
    suspend fun insert(attempt: Attempt): Long

    @Update
    suspend fun update(attempt: Attempt)

    @Query("SELECT * FROM attempts WHERE id = :id")
    suspend fun byId(id: Long): Attempt?

    @Query("DELETE FROM attempts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM attempts WHERE outcome = 'PENDING' ORDER BY scheduledAt DESC")
    suspend fun pending(): List<Attempt>

    /** The most recent prompt the user has not answered yet. */
    @Query(
        "SELECT * FROM attempts WHERE outcome IN ('PROMPTED', 'BLOCKED') AND response IS NULL " +
            "ORDER BY id DESC LIMIT 1"
    )
    suspend fun openPrompt(): Attempt?

    @Query("SELECT * FROM attempts WHERE outcome = 'PENDING' ORDER BY scheduledAt DESC LIMIT 1")
    fun observeNext(): Flow<Attempt?>

    @Query("SELECT * FROM attempts WHERE outcome != 'PENDING' ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<Attempt>>

    @Query("DELETE FROM attempts WHERE id NOT IN (SELECT id FROM attempts ORDER BY id DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}
