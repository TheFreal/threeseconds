package de.freal.threeseconds.service

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log

/**
 * Starts a service in the foreground without letting the system's type validation take
 * the whole app down.
 *
 * The `connectedDevice` type is only granted while BLUETOOTH_CONNECT is held, and that
 * is a runtime permission the user can refuse or revoke at any point. Letting
 * startForeground throw there would crash the app on the daily prompt -- the one moment
 * it must not -- so fall back to an untyped foreground service, and report failure so
 * the caller can stop cleanly rather than be killed for never calling startForeground.
 */
internal object ForegroundStart {

    fun start(service: Service, id: Int, notification: Notification): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                service.startForeground(
                    id,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
                return true
            } catch (e: Exception) {
                Log.w(TAG, "connectedDevice foreground type refused; retrying untyped", e)
            }
        }

        return try {
            service.startForeground(id, notification)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not enter the foreground", e)
            false
        }
    }

    private const val TAG = "ForegroundStart"
}
