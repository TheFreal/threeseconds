package de.freal.threeseconds.glasses

import android.app.Activity
import android.content.Context
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DonState
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** A flattened view of the one pair of glasses we care about. */
data class GlassesStatus(
    val deviceId: DeviceIdentifier? = null,
    val name: String? = null,
    val connected: Boolean = false,
    val worn: Boolean = false,
    val batteryLevel: Int? = null,
) {
    val available: Boolean get() = connected && deviceId != null
}

/**
 * Thin wrapper over [Wearables]. Device state (battery, worn, hinge) is metadata on
 * the device itself rather than a session capability, so we can answer "are the
 * glasses on your face right now" without opening a session or touching the camera.
 */
object GlassesManager {

    private val started = AtomicBoolean(false)

    /** True only once the SDK actually came up; the flows stay inert until then. */
    private val ready = AtomicBoolean(false)

    fun initialize(context: Context) {
        if (!started.compareAndSet(false, true)) return
        Wearables.initialize(context.applicationContext)
            .onSuccess { ready.set(true) }
            .onFailure { error, _ -> Log.e(TAG, "DAT initialize failed: ${error.description}") }
    }

    val isReady: Boolean get() = ready.get()

    val registrationState: StateFlow<RegistrationState> get() = Wearables.registrationState

    val isRegistered: Flow<Boolean> get() = registrationState.map { it == RegistrationState.REGISTERED }

    /**
     * Touching [Wearables.devices] before [initialize] throws, and a property
     * initializer on an `object` would do exactly that during class init. Wrapping the
     * whole thing in `flow { }` defers every SDK call to collection time.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val status: Flow<GlassesStatus> = flow {
        if (!ready.get()) {
            emit(GlassesStatus())
            return@flow
        }
        emitAll(
            Wearables.devices.flatMapLatest { ids ->
                val perDevice = ids.mapNotNull { id ->
                    Wearables.devicesMetadata[id]?.map { device -> id to device }
                }
                if (perDevice.isEmpty()) {
                    flowOf(GlassesStatus())
                } else {
                    combine(perDevice) { pairs -> pairs.toList().pick() }
                }
            }
        )
    }

    /** Prefer glasses that are being worn, then any that are connected. */
    private fun List<Pair<DeviceIdentifier, Device>>.pick(): GlassesStatus {
        val chosen = firstOrNull { (_, d) ->
            d.linkState == LinkState.CONNECTED && d.donState == DonState.DONNED
        } ?: firstOrNull { (_, d) -> d.linkState == LinkState.CONNECTED }
            ?: firstOrNull()
            ?: return GlassesStatus()

        val (id, device) = chosen
        return GlassesStatus(
            deviceId = id,
            name = device.name,
            connected = device.linkState == LinkState.CONNECTED,
            worn = device.donState == DonState.DONNED,
            batteryLevel = device.batteryLevel.takeIf { it in 1..100 },
        )
    }

    /**
     * Reads the glasses' state once, waiting only long enough for the SDK to report it.
     *
     * This replaces waiting for the user to put the glasses on. Watching for a DONNED
     * transition would mean the prompt always arrives moments after you reach for your
     * glasses, which is both predictable and expensive to listen for. Sampling at an
     * instant we chose instead keeps the timing independent of your behaviour, and the
     * whole call fits inside a broadcast receiver's budget -- no foreground service.
     */
    suspend fun sampleStatus(
        settleMs: Long = CONNECT_SETTLE_MS,
        wornGraceMs: Long = WORN_GRACE_MS,
    ): GlassesStatus {
        val connected = withTimeoutOrNull(settleMs) { status.firstOrNull { it.connected } }
            ?: return GlassesStatus()
        if (connected.worn) return connected

        // donState can trail the connection by a moment, so allow a short grace before
        // concluding the glasses are off. This is not waiting for the user to act.
        return withTimeoutOrNull(wornGraceMs) { status.firstOrNull { it.worn } } ?: connected
    }

    suspend fun hasCameraPermission(): Boolean =
        Wearables.checkPermissionStatus(Permission.CAMERA)
            .map { it is PermissionStatus.Granted }
            .getOrDefault(false)

    fun startRegistration(activity: Activity) = Wearables.startRegistration(activity)

    fun startUnregistration(activity: Activity) = Wearables.startUnregistration(activity)

    /** Long enough for the SDK to report a connected device, short enough for goAsync. */
    private const val CONNECT_SETTLE_MS = 5_000L
    private const val WORN_GRACE_MS = 2_000L

    private const val TAG = "GlassesManager"
}
