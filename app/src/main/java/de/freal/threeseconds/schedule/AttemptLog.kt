package de.freal.threeseconds.schedule

import de.freal.threeseconds.data.Attempt
import de.freal.threeseconds.data.AttemptDao
import de.freal.threeseconds.data.AttemptKind
import de.freal.threeseconds.data.AttemptOutcome
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Records every prompt alarm and its outcome so the debug screen can show why a prompt
 * did or did not reach the watch.
 *
 * At most one row is PENDING at a time: arming a new moment either drops the previous
 * pending row (it was replaced before its time, e.g. by a settings change, so nothing
 * was attempted) or, if its time had already passed without the receiver running,
 * marks it NEVER_FIRED.
 */
class AttemptLog(private val dao: AttemptDao) {

    /** A new moment was armed. */
    suspend fun scheduled(kind: AttemptKind, atMillis: Long) {
        val now = now()
        closePending(now)
        dao.insert(Attempt(kind = kind, scheduledAt = atMillis, createdAt = now))
        dao.trim(KEEP)
    }

    /** Prompts were turned off; whatever was armed will not go off. */
    suspend fun cancelled() = closePending(now())

    /**
     * The prompt alarm went off. Returns the row to resolve once the receiver knows
     * what it did. The row leaves PENDING immediately, so if the receiver dies before
     * resolving it the debug screen shows that rather than a phantom "scheduled".
     */
    suspend fun fired(): Long {
        val now = now()
        val pending = dao.pending().firstOrNull()
        if (pending == null) {
            // Armed by a build that did not log yet, or the row was trimmed.
            return dao.insert(
                Attempt(
                    kind = AttemptKind.FIRST,
                    scheduledAt = now,
                    createdAt = now,
                    firedAt = now,
                    outcome = AttemptOutcome.FIRING,
                    detail = "No schedule record for this alarm",
                )
            )
        }
        dao.update(pending.copy(firedAt = now, outcome = AttemptOutcome.FIRING))
        return pending.id
    }

    suspend fun resolve(id: Long, outcome: AttemptOutcome, detail: String? = null) {
        val row = dao.byId(id) ?: return
        dao.update(row.copy(outcome = outcome, detail = detail ?: row.detail))
    }

    suspend fun respond(id: Long, response: String) {
        val row = dao.byId(id) ?: return
        dao.update(row.copy(response = response))
    }

    /** A recording started from the app; returns the row for its result. */
    suspend fun manual(): Long {
        val now = now()
        return dao.insert(
            Attempt(
                kind = AttemptKind.MANUAL,
                scheduledAt = now,
                createdAt = now,
                firedAt = now,
                outcome = AttemptOutcome.MANUAL,
            )
        )
    }

    /** A test prompt's alarm went off; [armedFor] is when it was meant to. */
    suspend fun testFired(armedFor: Long): Long {
        val now = now()
        return dao.insert(
            Attempt(
                kind = AttemptKind.TEST,
                scheduledAt = armedFor,
                createdAt = now,
                firedAt = now,
                outcome = AttemptOutcome.FIRING,
            )
        )
    }

    suspend fun timings(id: Long, timings: String) {
        val row = dao.byId(id) ?: return
        dao.update(row.copy(timings = timings.ifBlank { null }))
    }

    /** Attaches [response] to the latest unanswered prompt and returns its id. */
    suspend fun respondToOpenPrompt(response: String): Long? {
        val row = dao.openPrompt() ?: return null
        dao.update(row.copy(response = response))
        return row.id
    }

    private suspend fun closePending(now: Long) {
        for (row in dao.pending()) {
            if (row.scheduledAt <= now) {
                dao.update(
                    row.copy(
                        outcome = AttemptOutcome.NEVER_FIRED,
                        detail = "Still waiting at ${clock(now)}, when the next moment was armed",
                    )
                )
            } else {
                dao.delete(row.id)
            }
        }
    }

    private fun now() = System.currentTimeMillis()

    private fun clock(millis: Long): String =
        CLOCK.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    private companion object {
        const val KEEP = 200

        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
