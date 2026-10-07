package de.freal.threeseconds.ui

import android.app.Application
import android.app.usage.UsageStatsManager
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.core.types.RegistrationState
import de.freal.threeseconds.container
import de.freal.threeseconds.data.AppSettings
import de.freal.threeseconds.data.Attempt
import de.freal.threeseconds.data.Clip
import de.freal.threeseconds.data.DayLog
import de.freal.threeseconds.data.StreakInfo
import de.freal.threeseconds.data.computeStreak
import de.freal.threeseconds.glasses.GlassesManager
import de.freal.threeseconds.glasses.GlassesStatus
import de.freal.threeseconds.montage.MontageBuilder
import de.freal.threeseconds.montage.MontageResult
import de.freal.threeseconds.notify.Notifications
import de.freal.threeseconds.service.RecordingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class HomeState(
    val settings: AppSettings = AppSettings(),
    val glasses: GlassesStatus = GlassesStatus(),
    val registration: RegistrationState = RegistrationState.UNAVAILABLE,
    val streak: StreakInfo = StreakInfo(0, 0, false),
    val clips: List<Clip> = emptyList(),
) {
    val isRegistered: Boolean get() = registration == RegistrationState.REGISTERED
}

/** System conditions that decide whether a prompt can fire and be seen. Polled, not observed. */
data class DebugChecks(
    val notificationsBlocked: String?,
    val doNotDisturb: Boolean,
    val exactAlarms: Boolean,
    val batteryUnrestricted: Boolean,
    val standbyBucket: Int,
    val checkedAt: Long,
)

sealed interface MontageState {
    data object Idle : MontageState
    data class Working(val done: Int, val total: Int) : MontageState
    data class Done(val message: String) : MontageState
    data class Error(val message: String) : MontageState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val container = app.container

    private val days: StateFlow<List<DayLog>> = container.database.days().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val state: StateFlow<HomeState> = combine(
        container.settings.settings,
        GlassesManager.status,
        GlassesManager.registrationState,
        container.database.clips().observeAll(),
        days,
    ) { settings, glasses, registration, clips, dayLogs ->
        HomeState(
            settings = settings,
            glasses = glasses,
            registration = registration,
            streak = computeStreak(dayLogs),
            clips = clips,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    val months: StateFlow<List<String>> = container.database.clips().observeMonths()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val nextAttempt: StateFlow<Attempt?> = container.database.attempts().observeNext()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val recentAttempts: StateFlow<List<Attempt>> = container.database.attempts().observeRecent(20)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _debugChecks = MutableStateFlow<DebugChecks?>(null)
    val debugChecks: StateFlow<DebugChecks?> = _debugChecks.asStateFlow()

    fun refreshDebugChecks() {
        val app = getApplication<Application>()
        _debugChecks.value = DebugChecks(
            notificationsBlocked = Notifications.promptBlockedReason(app),
            doNotDisturb = Notifications.doNotDisturbOn(app),
            exactAlarms = container.scheduler.exactAlarmsAllowed(),
            batteryUnrestricted = app.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(app.packageName),
            standbyBucket = app.getSystemService(UsageStatsManager::class.java).appStandbyBucket,
            checkedAt = System.currentTimeMillis(),
        )
    }

    private val _montage = MutableStateFlow<MontageState>(MontageState.Idle)
    val montage: StateFlow<MontageState> = _montage.asStateFlow()

    init {
        viewModelScope.launch { container.scheduler.ensureScheduled() }
    }

    fun recordNow() = RecordingService.start(getApplication())

    fun setEnabled(value: Boolean) = viewModelScope.launch {
        container.settings.setEnabled(value)
        container.scheduler.ensureScheduled(force = true)
    }

    fun setWindow(startMinute: Int, endMinute: Int) = viewModelScope.launch {
        container.settings.setWindow(startMinute, endMinute)
        container.scheduler.ensureScheduled(force = true)
    }

    fun setQuality(quality: String, frameRate: Int) = viewModelScope.launch {
        container.settings.setQuality(quality, frameRate)
    }

    fun setClipDuration(ms: Long) = viewModelScope.launch {
        container.settings.setClipDuration(ms)
    }

    fun buildMontage(month: String) = viewModelScope.launch {
        _montage.value = MontageState.Working(0, 0)
        val clips = container.database.clips().forMonth(month)
        val temp = File(getApplication<Application>().cacheDir, "montage_$month.mp4")

        val result = MontageBuilder(getApplication()).build(clips, temp) { done, total ->
            _montage.value = MontageState.Working(done, total)
        }

        _montage.value = when (result) {
            is MontageResult.Failure -> MontageState.Error(result.reason)
            is MontageResult.Success -> {
                runCatching { container.clipStore.saveMontage(temp, month) }
                    .onSuccess { temp.delete() }
                    .fold(
                        onSuccess = {
                            val skipped = if (result.skipped > 0) " (${result.skipped} skipped)" else ""
                            MontageState.Done("${result.clipCount} clips saved to Movies/ThreeSeconds/Montages$skipped")
                        },
                        onFailure = { MontageState.Error(it.message ?: "Could not save the montage") },
                    )
            }
        }
    }

    fun dismissMontage() { _montage.value = MontageState.Idle }
}
