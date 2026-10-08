package de.freal.threeseconds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.freal.threeseconds.data.DayMark
import de.freal.threeseconds.data.DayStory
import de.freal.threeseconds.data.StreakEffect
import de.freal.threeseconds.data.appToday
import de.freal.threeseconds.data.dayMark
import de.freal.threeseconds.data.inStreak
import de.freal.threeseconds.data.key
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

private val MISSED_RED = Color(0xFFE57373)

/**
 * A month of days in the style of a streak calendar: consecutive streak days are joined
 * by a band, days with a clip are filled, and every past day can be tapped.
 */
@Composable
fun StreakCalendarCard(state: HomeState, firstMonth: YearMonth, onDayClick: (LocalDate) -> Unit) {
    val today = appToday()
    val thisMonth = YearMonth.from(today)
    var month by rememberSaveable { mutableStateOf(thisMonth) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
            StreakHeader(state)

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { month = month.minusMonths(1) }, enabled = month > firstMonth) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month")
                }
                Text(
                    month.format(MONTH_TITLE),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { month = month.plusMonths(1) }, enabled = month < thisMonth) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Next month")
                }
            }

            MonthGrid(month, today, state, onDayClick)

            Legend(Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun StreakHeader(state: HomeState) {
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.LocalFireDepartment,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            when (state.streak.current) {
                0 -> "No streak yet"
                1 -> "1 day streak"
                else -> "${state.streak.current} day streak"
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (state.streak.longest > state.streak.current) {
            Text(
                "Best ${state.streak.longest}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonthGrid(month: YearMonth, today: LocalDate, state: HomeState, onDayClick: (LocalDate) -> Unit) {
    val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val lead = (month.atDay(1).dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    val days: List<LocalDate?> = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    val weeks = (days + List((7 - days.size % 7) % 7) { null }).chunked(7)

    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        repeat(7) { i ->
            Text(
                firstDayOfWeek.plus(i.toLong()).getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }

    weeks.forEach { week ->
        val marks = week.map { date ->
            date?.let { dayMark(it, today, state.dayLogs[it.key()], state.clipsByDay[it.key()].orEmpty().isNotEmpty()) }
        }
        Row(Modifier.fillMaxWidth()) {
            week.forEachIndexed { i, date ->
                val mark = marks[i]
                if (date == null || mark == null) {
                    Spacer(Modifier.weight(1f).height(CELL_HEIGHT))
                } else {
                    DayCell(
                        date = date,
                        mark = mark,
                        isToday = date == today,
                        joinLeft = mark.inStreak && marks.getOrNull(i - 1)?.inStreak == true,
                        joinRight = mark.inStreak && marks.getOrNull(i + 1)?.inStreak == true,
                        onClick = { onDayClick(date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    mark: DayMark,
    isToday: Boolean,
    joinLeft: Boolean,
    joinRight: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val band = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val primary = MaterialTheme.colorScheme.primary
    val kept = MaterialTheme.colorScheme.secondary

    Box(
        modifier
            .height(CELL_HEIGHT)
            .drawBehind {
                if (!mark.inStreak) return@drawBehind
                // A band segment from this cell's centre towards each streak neighbour; the
                // circle rounds off the ends of a run.
                val h = CIRCLE.toPx()
                val top = (size.height - h) / 2
                val cx = size.width / 2
                val left = if (joinLeft) 0f else cx
                val right = if (joinRight) size.width else cx
                if (right > left) drawRect(band, Offset(left, top), Size(right - left, h))
                drawCircle(band, radius = h / 2, center = Offset(cx, size.height / 2))
            }
            .then(if (mark != DayMark.FUTURE) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val circle = Modifier.size(CIRCLE).clip(CircleShape)
        val styled = when {
            mark == DayMark.CLIP -> circle.background(primary)
            mark == DayMark.KEPT -> circle.border(2.dp, kept, CircleShape)
            isToday -> circle.border(2.dp, primary, CircleShape)
            else -> circle
        }
        Box(styled, contentAlignment = Alignment.Center) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (mark.inStreak || isToday) FontWeight.Bold else FontWeight.Normal,
                color = when (mark) {
                    DayMark.CLIP -> MaterialTheme.colorScheme.onPrimary
                    DayMark.KEPT -> primary
                    DayMark.MISSED -> MISSED_RED
                    DayMark.OPEN -> MaterialTheme.colorScheme.onSurface
                    DayMark.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
                    DayMark.FUTURE -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                },
            )
        }
        if (mark == DayMark.MISSED) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp)
                    .size(5.dp).clip(CircleShape).background(MISSED_RED)
            )
        }
    }
}

@Composable
private fun Legend(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem(Modifier.background(MaterialTheme.colorScheme.primary), "Clip")
        LegendItem(Modifier.border(2.dp, MaterialTheme.colorScheme.secondary, CircleShape), "Streak kept")
        LegendItem(Modifier.background(MISSED_RED), "Missed")
    }
}

@Composable
private fun LegendItem(swatch: Modifier, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).then(swatch))
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * What tapping a day opens: the day's clips if there are any, otherwise the story of
 * why there are none.
 */
@Composable
fun DayDetail(date: LocalDate, state: HomeState, viewModel: MainViewModel, onDismiss: () -> Unit) {
    val clips = state.clipsByDay[date.key()].orEmpty()
    if (clips.isNotEmpty()) {
        ClipPlayerDialog(clips, onDismiss)
        return
    }

    var story by remember(date) { mutableStateOf<DayStory?>(null) }
    LaunchedEffect(date) { story = viewModel.storyFor(date) }
    val s = story ?: return

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = {
            Column {
                Text(
                    date.format(DAY_TITLE),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(s.title)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s.body)
                when (s.effect) {
                    StreakEffect.KEPT -> EffectChip("Streak kept", MaterialTheme.colorScheme.secondary)
                    StreakEffect.BROKEN -> EffectChip("Streak broken", MISSED_RED)
                    StreakEffect.OPEN, StreakEffect.NONE -> Unit
                }
            }
        },
    )
}

@Composable
private fun EffectChip(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

private val CELL_HEIGHT = 44.dp
private val CIRCLE = 34.dp
private val MONTH_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
private val DAY_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")
