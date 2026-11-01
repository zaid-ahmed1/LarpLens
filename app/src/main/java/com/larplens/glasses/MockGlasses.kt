package com.larplens.glasses

import android.content.Context
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing

/**
 * Simulated Ray-Ban Meta glasses whose "camera" is the phone's back camera, so you can point the
 * phone at a name tag and run the full pipeline without hardware. Needs android.permission.CAMERA
 * granted first (separate from the DAT camera permission, which the mock grants).
 */
class MockGlassesController(context: Context) {
  private val kit = MockDeviceKit.getInstance(context)
  private var glasses: MockGlasses? = null

  /** Returns an error description on failure, or null on success. */
  fun enable(): String? {
    kit.enable(MockDeviceKitConfig(initiallyRegistered = true, initialPermissionsGranted = true))
    return kit.pairGlasses(GlassesModel.RAYBAN_META)
        .fold(
            onSuccess = { paired ->
              // A session only starts on glasses that are powered on, unfolded, and worn
              paired.powerOn()
              paired.unfold()
              paired.don()
              paired.services.camera.setCameraFeed(CameraFacing.BACK)
              glasses = paired
              null
            },
            onFailure = { error, _ -> error.description },
        )
  }

  fun disable() {
    glasses?.let { kit.unpairDevice(it) }
    glasses = null
    kit.disable()
  }
}
