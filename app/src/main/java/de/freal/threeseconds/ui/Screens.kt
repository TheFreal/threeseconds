package de.freal.threeseconds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import de.freal.threeseconds.data.AppSettings
import de.freal.threeseconds.data.Clip
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private enum class Tab(val label: String) {
    Today("Today"), Clips("Clips"), Montage("Montage"), Settings("Settings"), Debug("Debug")
}

@Composable
fun AppScaffold(
    viewModel: MainViewModel,
    cameraGranted: Boolean,
    onConnectGlasses: () -> Unit,
    onGrantCamera: () -> Unit,
) {
    var tab by remember { mutableStateOf(Tab.Today) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.Today -> Icons.Default.CameraAlt
                                    Tab.Clips -> Icons.Default.PhotoLibrary
                                    Tab.Montage -> Icons.Default.Movie
                                    Tab.Settings -> Icons.Default.Settings
                                    Tab.Debug -> Icons.Default.BugReport
                                },
                                contentDescription = t.label,
                            )
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when (tab) {
                Tab.Today -> TodayScreen(state, viewModel, cameraGranted, onConnectGlasses, onGrantCamera)
                Tab.Clips -> ClipsScreen(state.clips)
                Tab.Montage -> MontageScreen(viewModel)
                Tab.Settings -> SettingsScreen(state.settings, viewModel)
                Tab.Debug -> DebugScreen(viewModel, state.settings)
            }
        }
    }
}

@Composable
private fun TodayScreen(
    state: HomeState,
    viewModel: MainViewModel,
    cameraGranted: Boolean,
    onConnectGlasses: () -> Unit,
    onGrantCamera: () -> Unit,
) {
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
    selectedDay?.let { DayDetail(it, state, viewModel, onDismiss = { selectedDay = null }) }

    // Never page back past the first month that has anything to show.
    val firstMonth = listOfNotNull(
        viewModel.installDay,
        state.dayLogs.keys.minOrNull()?.let(LocalDate::parse),
        state.clipsByDay.keys.minOrNull()?.let(LocalDate::parse),
        LocalDate.now(),
    ).min().let(YearMonth::from)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TodayHeadline(state, cameraGranted)

        StreakCalendarCard(state, firstMonth, onDayClick = { selectedDay = it })

        // Once set up, the glasses' state lives in the headline's subtitle; the card only
        // remains while there is something to do.
        if (!state.isRegistered || !cameraGranted) {
            GlassesCard(state, cameraGranted, onConnectGlasses, onGrantCamera)
        }

        if (state.isRegistered && cameraGranted) {
            Button(
                onClick = viewModel::recordNow,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = state.glasses.available,
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (state.glasses.available) "Record three seconds now" else "Glasses not connected")
            }
        }

        Text(
            "The daily prompt arrives at a random moment while you are wearing your glasses, " +
                "and lands on your watch. Record or snooze from there.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TodayHeadline(state: HomeState, cameraGranted: Boolean) {
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (state.streak.recordedToday) "Today is in the bag." else "Today is still open.",
            style = MaterialTheme.typography.headlineSmall,
        )
        val g = state.glasses
        val name = g.name ?: "Your glasses"
        val (ok, line) = when {
            !state.isRegistered -> false to "Glasses not linked to 3S yet"
            !cameraGranted -> false to "Camera access on the glasses not allowed yet"
            !g.connected -> false to "$name: not connected"
            !g.worn -> false to "$name: connected, not being worn"
            else -> true to "$name: connected and being worn"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(if (ok) Color(0xFF6BD68A) else Color(0xFF6E6E78))
            )
            Spacer(Modifier.size(8.dp))
            Text(
                line + (g.batteryLevel?.takeIf { g.connected }?.let { "  ·  $it%" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GlassesCard(
    state: HomeState,
    cameraGranted: Boolean,
    onConnectGlasses: () -> Unit,
    onGrantCamera: () -> Unit,
) {
    // Only shown while setup is unfinished; afterwards the headline carries the state.
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ray-Ban Meta", style = MaterialTheme.typography.titleMedium)

            when {
                !state.isRegistered -> {
                    Text(
                        "Connect this app to Meta AI so it can reach your glasses.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onConnectGlasses) { Text("Connect glasses") }
                }

                !cameraGranted -> {
                    Text(
                        "Allow camera access on the glasses to record clips.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onGrantCamera) { Text("Allow camera") }
                }
            }
        }
    }
}

@Composable
private fun ClipsScreen(clips: List<Clip>) {
    if (clips.isEmpty()) {
        EmptyState("No clips yet", "Your first three seconds will show up here.")
        return
    }

    var playing by remember { mutableStateOf<Clip?>(null) }
    playing?.let { ClipPlayerDialog(listOf(it), onDismiss = { playing = null }) }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        modifier = Modifier.fillMaxSize().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(clips, key = { it.id }) { clip -> ClipThumb(clip, onClick = { playing = clip }) }
    }
}

@Composable
private fun ClipThumb(clip: Clip, onClick: () -> Unit) {
    Column {
        Box(
            Modifier.fillMaxWidth().aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(clip.uri)
                    .decoderFactory { result, options, _ -> VideoFrameDecoder(result.source, options) }
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Play",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(6.dp),
            )
        }
        Text(
            clip.day,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MontageScreen(viewModel: MainViewModel) {
    val months by viewModel.months.collectAsStateWithLifecycle()
    val montage by viewModel.montage.collectAsStateWithLifecycle()

    if (months.isEmpty()) {
        EmptyState("Nothing to stitch yet", "Montages appear once you have a month with clips in it.")
        return
    }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Monthly montages", style = MaterialTheme.typography.titleLarge)

        when (val m = montage) {
            is MontageState.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
                Text(if (m.total > 0) "Stitching ${m.done} of ${m.total}" else "Starting")
            }
            is MontageState.Done -> ResultLine(m.message) { viewModel.dismissMontage() }
            is MontageState.Error -> ResultLine(m.message) { viewModel.dismissMontage() }
            MontageState.Idle -> Unit
        }

        months.forEach { month ->
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(prettyMonth(month), style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(
                    onClick = { viewModel.buildMontage(month) },
                    enabled = montage !is MontageState.Working,
                ) { Text("Create") }
            }
        }
    }
}

@Composable
private fun ResultLine(message: String, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onDismiss, modifier = Modifier.padding(top = 8.dp)) { Text("OK") }
        }
    }
}

@Composable
private fun SettingsScreen(settings: AppSettings, viewModel: MainViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Daily prompt", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Ask me once a day while I'm wearing the glasses.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = settings.enabled, onCheckedChange = viewModel::setEnabled)
        }

        HorizontalDivider()

        Column {
            Text("Window", style = MaterialTheme.typography.titleMedium)
            WindowSlider(settings, viewModel)
        }

        HorizontalDivider()

        Text("Clip length: ${settings.clipDurationMs / 1000}s", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = (settings.clipDurationMs / 1000f),
            onValueChange = { viewModel.setClipDuration((it.toInt() * 1000).toLong()) },
            valueRange = 2f..10f,
            steps = 7,
        )

        HorizontalDivider()

        Text("Quality", style = MaterialTheme.typography.titleMedium)
        val qualities = listOf("LOW", "MEDIUM", "HIGH")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            qualities.forEachIndexed { i, q ->
                SegmentedButton(
                    selected = settings.videoQuality == q,
                    onClick = { viewModel.setQuality(q, settings.frameRate) },
                    shape = SegmentedButtonDefaults.itemShape(i, qualities.size),
                ) { Text(q.lowercase().replaceFirstChar { it.uppercase() }) }
            }
        }
        Text(
            "Bluetooth bandwidth is the real limit. Lower settings often look better per frame.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One slider with two thumbs, in whole hours. The window is only saved when a thumb is
 * let go: every save re-arms the day's prompt, which should not happen on each drag tick.
 */
@Composable
private fun WindowSlider(settings: AppSettings, viewModel: MainViewModel) {
    val saved = settings.windowStartMinute / 60f..settings.windowEndMinute / 60f
    var dragging by remember { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    val range = dragging ?: saved

    Text(
        "${minuteLabel(range.start.roundToInt() * 60)} to ${minuteLabel(range.endInclusive.roundToInt() * 60)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    RangeSlider(
        value = range,
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let {
                val start = it.start.roundToInt()
                val end = it.endInclusive.roundToInt().coerceAtLeast(start + 1)
                viewModel.setWindow(start * 60, end * 60)
            }
            dragging = null
        },
        valueRange = 0f..24f,
        steps = 23,
    )
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun minuteLabel(minuteOfDay: Int): String =
    if (minuteOfDay >= 24 * 60) "24:00"
    else LocalTime.of(minuteOfDay / 60, minuteOfDay % 60).format(DateTimeFormatter.ofPattern("HH:mm"))

private fun prettyMonth(month: String): String =
    runCatching { YearMonth.parse(month).format(DateTimeFormatter.ofPattern("MMMM yyyy")) }
        .getOrDefault(month)
