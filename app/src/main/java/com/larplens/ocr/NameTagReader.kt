package com.larplens.ocr

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

data class TagReading(
    /** Every line OCR saw, as read. */
    val rawLines: List<String>,
    /** The extracted name, or null if nothing name-like was read. */
    val name: String?,
    val elapsedMs: Long,
)

/** Reads the text on a cropped name tag. */
interface NameTagReader : AutoCloseable {
  val label: String
  /** Agreeing reads needed before a name is confirmed. */
  val readsNeeded: Int
  /** Minimum gap between reads while a tag stays locked. */
  val minIntervalMs: Long
  /** Stop reading a locked tag after this many attempts (or once confirmed). */
  val maxReadsPerLock: Int

  suspend fun read(crop: Bitmap): TagReading

  /** Optional: open connections etc. ahead of the first read. */
  suspend fun warmUp() {}
}

/**
 * Google ML Kit on-device text recognition (bundled Latin model: offline, ~50-200 ms).
 *
 * It's trained mostly on print. Neat block capitals in marker usually read; cursive and messy
 * handwriting often won't. That's the case for the vision-LLM fallback.
 */
class MlKitNameTagReader : NameTagReader {
  override val label = "ML Kit (on-device)"
  // Cheap and flickery: read often, confirm on agreement
  override val readsNeeded = 2
  override val minIntervalMs = 300L
  override val maxReadsPerLock = Int.MAX_VALUE

  private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

  override suspend fun read(crop: Bitmap): TagReading {
    val started = System.nanoTime()
    val input = upscale(crop)
    val text = suspendCancellableCoroutine { continuation ->
      recognizer
          .process(InputImage.fromBitmap(input, 0))
          .addOnSuccessListener { continuation.resume(it) }
          .addOnFailureListener { continuation.resumeWithException(it) }
    }
    val lines =
        text.textBlocks.flatMap { block ->
          block.lines.map { line ->
            val box = line.boundingBox
            OcrLine(line.text, top = box?.top ?: 0, height = box?.height() ?: 0)
          }
        }
    return TagReading(
        rawLines = lines.map { it.text },
        name = NameExtractor.extract(lines),
        elapsedMs = (System.nanoTime() - started) / 1_000_000,
    )
  }

  /** Stream crops are small; ML Kit wants characters >= ~16 px tall, so scale up to ~800 px wide. */
  private fun upscale(crop: Bitmap): Bitmap {
    if (crop.width >= TARGET_WIDTH) return crop
    val factor = TARGET_WIDTH.toFloat() / crop.width
    return crop.scale(TARGET_WIDTH, (crop.height * factor).toInt().coerceAtLeast(1), filter = true)
  }

  override fun close() = recognizer.close()

  private companion object {
    const val TARGET_WIDTH = 800
  }
}
