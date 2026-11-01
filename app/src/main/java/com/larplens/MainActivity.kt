package com.larplens

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.CAMERA
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.viewModels
import com.larplens.ui.LarpLensScreen
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {
  private val viewModel: LarpLensViewModel by viewModels()

  // Bluetooth must be granted before the DAT SDK is initialized
  private val bluetoothPermissionLauncher =
      registerForActivityResult(RequestPermission()) { granted ->
        if (granted) viewModel.initializeSdk()
        else viewModel.showMessage("Bluetooth permission is required to talk to the glasses")
      }

  // Phone camera: only needed so mock glasses can use the phone's camera as their feed
  private val phoneCameraPermissionLauncher =
      registerForActivityResult(RequestPermission()) { granted ->
        if (granted) viewModel.setMockEnabled(true)
        else viewModel.showMessage("Mock glasses need the phone camera permission")
      }

  // DAT permissions (glasses camera) are granted through the Meta AI app
  private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
  private val permissionMutex = Mutex()
  private val wearablesPermissionLauncher =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        permissionContinuation?.resume(result.getOrDefault(PermissionStatus.Denied))
        permissionContinuation = null
      }

  private suspend fun requestWearablesPermission(permission: Permission): PermissionStatus =
      permissionMutex.withLock {
        suspendCancellableCoroutine { continuation ->
          permissionContinuation = continuation
          continuation.invokeOnCancellation { permissionContinuation = null }
          wearablesPermissionLauncher.launch(permission)
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      LarpLensScreen(
          viewModel = viewModel,
          onRegister = { Wearables.startRegistration(this) },
          onStartCamera = { viewModel.startCamera(::requestWearablesPermission) },
          onMockToggle = { enabled ->
            if (enabled) phoneCameraPermissionLauncher.launch(CAMERA)
            else viewModel.setMockEnabled(false)
          },
      )
    }
    bluetoothPermissionLauncher.launch(BLUETOOTH_CONNECT)
  }

  override fun onResume() {
    super.onResume()
    viewModel.refreshAudioRoute()
  }
}
