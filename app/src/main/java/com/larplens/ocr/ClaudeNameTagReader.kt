package com.larplens.ocr

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCountTokensParams
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigDisabled
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Reads a handwritten name tag with Claude Haiku 5.5 vision. One call per lock is usually enough.
 *
 * Tuned for latency: the crop is downscaled to <= 512 px (~175 image tokens), thinking is off,
 * effort is low, and the answer is a few tokens. The client is created once so the HTTPS
 * connection stays warm between reads.
 *
 * DEV ONLY: the API key is compiled into the app. Route through a backend before sharing builds.
 */
class ClaudeNameTagReader(apiKey: String) : NameTagReader {
  override val label = "Claude Haiku 5.5"
  // A good read is a good read: confirm on the first name, retry a couple of times if none
  override val readsNeeded = 1
  override val minIntervalMs = 700L
  override val maxReadsPerLock = 3

  private val client: AnthropicClient =
      AnthropicOkHttpClient.builder()
          .apiKey(apiKey)
          .timeout(Duration.ofSeconds(8))
          .maxRetries(1)
          .build()

  override suspend fun read(crop: Bitmap): TagReading =
      withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val params =
            MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(64L) // room for the JSON wrapper around a short name
                .thinking(ThinkingConfigDisabled.builder().build())
                .outputConfig(
                    OutputConfig.builder()
                        .effort(OutputConfig.Effort.LOW)
                        // Structured output: the reply can only be this JSON, so no descriptions
                        .format(NAME_FORMAT)
                        .build())
                .system(SYSTEM_PROMPT)
                .addUserMessageOfContentBlockParams(
                    listOf(
                        ContentBlockParam.ofImage(
                            ImageBlockParam.builder()
                                .source(
                                    Base64ImageSource.builder()
                                        .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                        .data(encodeJpeg(crop))
                                        .build())
                                .build()),
                        ContentBlockParam.ofText(
                            TextBlockParam.builder().text("Name on this tag?").build()),
                    ))
                .build()

        val response = client.messages().create(params)
        val json =
            if (response.stopReason().orElse(null) == StopReason.REFUSAL) ""
            else response.content().joinToString("") { block -> block.text().map { it.text() }.orElse("") }
        val name = parseName(json)
        TagReading(
            rawLines = listOf(name ?: CANT_READ),
            name = name,
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
        )
      }

  /** Token counting is free; it opens the TLS connection so the first real read is faster. */
  override suspend fun warmUp() {
    withContext(Dispatchers.IO) {
      runCatching {
        client
            .messages()
            .countTokens(MessageCountTokensParams.builder().model(MODEL).addUserMessage("hi").build())
      }
    }
  }

  /** {"readable": true, "name": "Zaid"} -> "Zaid"; anything else (or anything long) -> null. */
  private fun parseName(json: String): String? {
    val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
    if (!obj.optBoolean("readable", false)) return null
    val raw = obj.optString("name").trim()
    // Backstop: a name is a few short words, never a description
    if (raw.length > MAX_NAME_CHARS || raw.split(Regex("\\s+")).size > MAX_NAME_WORDS) return null
    return NameExtractor.extract(listOf(OcrLine(raw, top = 0, height = 1)))
  }

  private fun encodeJpeg(crop: Bitmap): String {
    val longest = maxOf(crop.width, crop.height)
    val image =
        if (longest <= MAX_SIDE) crop
        else {
          val f = MAX_SIDE.toFloat() / longest
          crop.scale((crop.width * f).toInt().coerceAtLeast(1), (crop.height * f).toInt().coerceAtLeast(1))
        }
    val out = ByteArrayOutputStream()
    image.compress(Bitmap.CompressFormat.JPEG, 85, out)
    return Base64.getEncoder().encodeToString(out.toByteArray())
  }

  override fun close() = client.close()

  private companion object {
    const val MODEL = "claude-haiku-5-5"
    const val MAX_SIDE = 512
    const val MAX_NAME_CHARS = 40
    const val MAX_NAME_WORDS = 4
    const val CANT_READ = "Can't Read Name"

    val SYSTEM_PROMPT =
        """
        You read handwritten conference name tags from a low-resolution camera crop.
        Set "name" to the person's name exactly as written: just the name, no description,
        company, pronouns, handle, or template text such as "HELLO my name is".
        If no name is clearly legible, set "readable" to false and "name" to "". Do not guess.
        """
            .trimIndent()

    val NAME_FORMAT: JsonOutputFormat =
        JsonOutputFormat.builder()
            .schema(
                JsonOutputFormat.Schema.builder()
                    .putAdditionalProperty("type", JsonValue.from("object"))
                    .putAdditionalProperty(
                        "properties",
                        JsonValue.from(
                            mapOf(
                                "readable" to mapOf("type" to "boolean"),
                                "name" to mapOf("type" to "string"),
                            )))
                    .putAdditionalProperty("required", JsonValue.from(listOf("readable", "name")))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build())
            .build()
  }
}
