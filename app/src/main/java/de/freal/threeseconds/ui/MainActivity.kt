package de.freal.threeseconds.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import de.freal.threeseconds.glasses.GlassesManager
import de.freal.threeseconds.ui.theme.ThreeSecondsTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var cameraGranted by mutableStateOf(false)

    private val systemPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val datCameraPermission =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            cameraGranted = result.getOrNull() is PermissionStatus.Granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestSystemPermissions()

        setContent {
            ThreeSecondsTheme {
                AppScaffold(
                    viewModel = viewModel,
                    cameraGranted = cameraGranted,
                    onConnectGlasses = { GlassesManager.startRegistration(this) },
                    onGrantCamera = { datCameraPermission.launch(Permission.CAMERA) },
                )
            }
        }
    }

    /**
     * POST_NOTIFICATIONS is what lets the prompt reach the watch at all, and
     * BLUETOOTH_CONNECT is what lets the capture service claim the `connectedDevice`
     * foreground type. Without the latter the daily recording cannot start.
     */
    private fun requestSystemPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (wanted.isNotEmpty()) systemPermissions.launch(wanted.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        // The DAT camera grant can be changed outside this app, in the Meta AI settings.
        lifecycleScope.launch { cameraGranted = GlassesManager.hasCameraPermission() }
    }
}
