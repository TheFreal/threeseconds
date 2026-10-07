package de.freal.threeseconds.ui

import android.app.usage.UsageStatsManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.freal.threeseconds.data.AppSettings
import de.freal.threeseconds.data.Attempt
import de.freal.threeseconds.data.AttemptOutcome
import de.freal.threeseconds.data.key
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val GOOD = Color(0xFF6BD68A)
private val NEUTRAL = Color(0xFF6E6E78)
private val BAD = Color(0xFFE57373)

/**
 * What the scheduler plans next, what could stop it, and how the last attempts went.
 * Everything here is read-only.
 */
@Composable
fun DebugScreen(viewModel: MainViewModel, settings: AppSettings) {
    val next by viewModel.nextAttempt.collectAsStateWithLifecycle()
    val recent by viewModel.recentAttempts.collectAsStateWithLifecycle()
    val checks by viewModel.debugChecks.collectAsStateWithLifecycle()

    // The checks are system state with no change callbacks, and the countdown to the
    // next prompt needs a clock, so both refresh on a timer while the tab is open.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshDebugChecks()
            delay(15_000)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { NextCard(settings, next, checks?.checkedAt ?: System.currentTimeMillis()) }
        checks?.let { item { ChecksCard(it) } }
        item {
            Text(
                "Last ${recent.size} attempts",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (recent.isEmpty()) {
            item {
                Text(
                    "No prompt alarm has gone off since logging was added.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(recent, key = { it.id }) { AttemptRow(it) }
    }
}

@Composable
private fun NextCard(settings: AppSettings, next: Attempt?, now: Long) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Next prompt", style = MaterialTheme.typography.titleMedium)

            val at = settings.scheduledAtMillis
            when {
                !settings.enabled -> Muted("The daily prompt is switched off in Settings.")
                at <= 0L -> Muted("Nothing has been scheduled yet.")
                else -> {
                    Text(dateTime(at), style = MaterialTheme.typography.headlineSmall)
                    if (at >= now) {
                        Muted("In ${span(at - now)}")
                    } else {
                        Text(
                            "Overdue by ${span(now - at)}: the alarm has not gone off",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BAD,
                        )
                    }
                    val kind = next?.takeIf { it.scheduledAt == at }?.kind?.label
                    Muted(kind ?: "Armed before attempt logging existed")
                    if (settings.scheduledDay == LocalDate.now().key()) {
                        Muted(
                            "Today: attempt ${settings.attemptCount + 1}, " +
                                "snoozes used ${settings.snoozeCount}/${AppSettings.MAX_SNOOZES}"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChecksCard(checks: DebugChecks) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("What can stop it", style = MaterialTheme.typography.titleMedium)
            CheckRow(
                "Notifications",
                if (checks.notificationsBlocked == null) GOOD else BAD,
                checks.notificationsBlocked ?: "The prompt can be shown",
            )
            CheckRow(
                "Do Not Disturb",
                if (checks.doNotDisturb) BAD else GOOD,
                if (checks.doNotDisturb) "On: the prompt is posted but may stay silent" else "Off",
            )
            CheckRow(
                "Exact alarms",
                if (checks.exactAlarms) GOOD else BAD,
                if (checks.exactAlarms) "Allowed" else "Not allowed: the prompt can arrive minutes late",
            )
            CheckRow(
                "Battery",
                NEUTRAL,
                if (checks.batteryUnrestricted) "Not optimised" else "Optimised (system default)",
            )
            val restricted = checks.standbyBucket >= UsageStatsManager.STANDBY_BUCKET_RESTRICTED
            CheckRow(
                "Standby bucket",
                if (restricted) BAD else NEUTRAL,
                bucketLabel(checks.standbyBucket) +
                    if (restricted) ": the system heavily limits background work" else "",
            )
        }
    }
}

@Composable
private fun CheckRow(label: String, color: Color, note: String) {
    Row(verticalAlignment = Alignment.Top) {
        Dot(color, Modifier.padding(top = 6.dp))
        Spacer(Modifier.size(10.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Muted(note)
        }
    }
}

@Composable
private fun AttemptRow(attempt: Attempt) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    dateTime(attempt.scheduledAt),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    attempt.kind.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            attempt.firedAt?.let { fired ->
                val late = fired - attempt.scheduledAt
                Muted(
                    "Fired ${clock(fired)}" + if (late >= 60_000L) " (${span(late)} late)" else ""
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(outcomeColor(attempt.outcome))
                Spacer(Modifier.size(8.dp))
                Text(
                    attempt.outcome.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            attempt.detail?.let { Muted(it) }
            attempt.response?.let { Text("→ $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
private fun Muted(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun outcomeColor(outcome: AttemptOutcome): Color = when (outcome) {
    AttemptOutcome.PROMPTED -> GOOD
    AttemptOutcome.NOT_WORN,
    AttemptOutcome.ALREADY_RECORDED,
    AttemptOutcome.DISABLED,
    AttemptOutcome.PENDING -> NEUTRAL
    AttemptOutcome.FIRING,
    AttemptOutcome.BLOCKED,
    AttemptOutcome.NEVER_FIRED,
    AttemptOutcome.FAILED -> BAD
}

private fun bucketLabel(bucket: Int): String = when {
    bucket <= UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "Active"
    bucket <= UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "Working set"
    bucket <= UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "Frequent"
    bucket <= UsageStatsManager.STANDBY_BUCKET_RARE -> "Rare"
    else -> "Restricted"
}

private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun dateTime(millis: Long): String =
    DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

private fun clock(millis: Long): String =
    CLOCK.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

private fun span(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
        minutes >= 1 -> "${minutes}m"
        else -> "${ms / 1000}s"
    }
}
