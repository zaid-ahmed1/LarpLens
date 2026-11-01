package com.larplens.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Plays 16-bit mono PCM to whatever the phone's media output is.
 *
 * The DAT SDK has no audio-output API. The glasses are a regular Bluetooth headset, so media-usage
 * audio goes to them over A2DP when they're the active output. Don't enable HFP / communication
 * mode alongside this, or output drops to mono call quality.
 */
class GlassesAudioPlayer(context: Context) {
  private val audioManager = context.getSystemService(AudioManager::class.java)

  /** True when a Bluetooth A2DP output (e.g. the glasses) is connected to the phone. */
  fun isA2dpConnected(): Boolean =
      audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
      }

  /** A short two-note chime, generated locally, for checking the route without network. */
  suspend fun playTestTone(onRouted: (String) -> Unit = {}) =
      playNotes(listOf(660.0 to 0.18, 880.0 to 0.30), volume = 0.35, onRouted = onRouted)

  /** A quick, quiet rising blip: "I see a name tag". */
  suspend fun playDetectedEarcon() = playNotes(listOf(1046.5 to 0.06, 1568.0 to 0.09), volume = 0.2)

  private suspend fun playNotes(
      notes: List<Pair<Double, Double>>,
      volume: Double,
      onRouted: (String) -> Unit = {},
  ) {
    val rate = 44_100
    playPcm(rate, onRouted) { write ->
      for ((freq, seconds) in notes) {
        val count = (rate * seconds).toInt()
        val bytes = ByteArray(count * 2)
        for (n in 0 until count) {
          // 10 ms fade in/out so the notes don't click
          val fade = min(1.0, min(n, count - n) / (rate * 0.008))
          val s = (sin(2 * PI * freq * n / rate) * volume * fade * Short.MAX_VALUE).toInt()
          bytes[2 * n] = (s and 0xFF).toByte()
          bytes[2 * n + 1] = ((s shr 8) and 0xFF).toByte()
        }
        write(bytes, bytes.size)
      }
    }
  }

  /**
   * Streams PCM produced by [produce] into an AudioTrack and returns once it has finished playing.
   * [produce] gets a blocking `write(bytes, length)` and may call it any number of times.
   * [onRouted] reports where the audio actually went (e.g. "Bluetooth A2DP · Ray-Ban Meta").
   */
  suspend fun playPcm(
      sampleRate: Int,
      onRouted: (String) -> Unit = {},
      produce: suspend (write: (ByteArray, Int) -> Unit) -> Unit,
  ) =
      withContext(Dispatchers.IO) {
        val minBuffer =
            AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(minBuffer * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

        var framesWritten = 0L
        var carry: Byte? = null // keep writes 2-byte aligned when the network splits a sample
        var routeReported = false
        try {
          track.play()
          produce { bytes, length ->
            var data = bytes
            var len = length
            carry?.let {
              data = ByteArray(len + 1).also { joined ->
                joined[0] = it
                System.arraycopy(bytes, 0, joined, 1, len)
              }
              len += 1
              carry = null
            }
            if (len % 2 == 1) {
              carry = data[len - 1]
              len -= 1
            }
            var offset = 0
            while (offset < len) {
              val n = track.write(data, offset, len - offset)
              if (n < 0) throw IllegalStateException("AudioTrack.write failed: $n")
              offset += n
            }
            framesWritten += len / 2
            if (!routeReported) {
              track.routedDevice?.let {
                routeReported = true
                onRouted(describe(it))
              }
            }
          }
          // Wait for the buffered tail to actually play out before releasing
          val deadline = System.currentTimeMillis() + 2_000 + framesWritten * 1000 / sampleRate
          while (track.playbackHeadPosition.toLong() < framesWritten &&
              System.currentTimeMillis() < deadline) {
            ensureActive()
            delay(20)
          }
          if (!routeReported) track.routedDevice?.let { onRouted(describe(it)) }
        } finally {
          track.stop()
          track.release()
        }
      }

  private fun describe(device: AudioDeviceInfo): String {
    val kind =
        when (device.type) {
          AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
          AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO (call quality)"
          AudioDeviceInfo.TYPE_BLE_HEADSET,
          AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE"
          AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
          AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
          AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
          else -> "Device type ${device.type}"
        }
    val name = device.productName?.toString().orEmpty()
    return if (name.isBlank()) kind else "$kind · $name"
  }
}
