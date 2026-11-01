package com.larplens.audio

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONObject

/**
 * ElevenLabs streaming TTS: POST /v1/text-to-speech/{voice_id}/stream with raw PCM output, piped
 * straight into [GlassesAudioPlayer] so playback starts on the first chunk.
 *
 * DEV ONLY: calls ElevenLabs directly with a key compiled into the app. Before anyone else
 * installs a build, move this behind a backend that holds the key.
 */
class ElevenLabsTts(
    private val apiKey: String,
    private val voiceId: String,
    private val modelId: String,
) {
  val isConfigured: Boolean
    get() = apiKey.isNotBlank() && voiceId.isNotBlank()

  /** SSML `<break>` pauses work on Flash/Turbo/Multilingual; Eleven v3 and v4 ignore them. */
  val supportsBreakTags: Boolean
    get() = !modelId.startsWith("eleven_v3") && !modelId.startsWith("eleven_v4")

  suspend fun speak(text: String, player: GlassesAudioPlayer, onRouted: (String) -> Unit = {}) {
    check(isConfigured) { "Set elevenlabs_api_key in local.properties and rebuild" }
    val voice = URLEncoder.encode(voiceId, "UTF-8")
    val url = URL("$BASE_URL/v1/text-to-speech/$voice/stream?output_format=pcm_$SAMPLE_RATE")
    val body =
        JSONObject()
            .put("text", text)
            .put("model_id", modelId)
            // A touch slower than normal (1.0) so a whisper is easy to follow; range is 0.7-1.2
            .put("voice_settings", JSONObject().put("speed", SPEED))
            .toString()

    player.playPcm(SAMPLE_RATE, onRouted) { write ->
      val connection = url.openConnection() as HttpURLConnection
      try {
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.doOutput = true
        connection.setRequestProperty("xi-api-key", apiKey)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "audio/pcm")
        connection.outputStream.use { it.write(body.toByteArray()) }

        val code = connection.responseCode
        if (code !in 200..299) {
          val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
          throw IOException("ElevenLabs HTTP $code ${detail.take(300)}")
        }
        connection.inputStream.use { input ->
          val buffer = ByteArray(4096)
          while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            if (n > 0) write(buffer, n)
          }
        }
      } finally {
        connection.disconnect()
      }
    }
  }

  private companion object {
    const val BASE_URL = "https://api.elevenlabs.io"
    // Raw signed 16-bit little-endian mono; matches GlassesAudioPlayer's AudioTrack format
    const val SAMPLE_RATE = 22_050
    const val SPEED = 0.9
  }
}
