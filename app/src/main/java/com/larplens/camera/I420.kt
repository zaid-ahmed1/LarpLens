package com.larplens.camera

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * Raw (compressVideo = false) DAT frames are I420: a full-size Y plane followed by quarter-size U
 * and V planes. The docs only say "YUV"; I420 is what the CameraAccess sample (<= 0.8.0) decoded.
 */
object I420 {

  /** Converts I420 bytes to ARGB pixels using BT.709 limited-range coefficients. */
  fun toArgb(yuv: ByteArray, width: Int, height: Int, out: IntArray = IntArray(width * height)): IntArray {
    require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
      "I420 needs even, positive dimensions, got ${width}x$height"
    }
    val frameSize = width * height
    require(yuv.size >= frameSize * 3 / 2) { "Buffer too small for ${width}x$height I420" }
    require(out.size >= frameSize) { "Output too small" }

    val uOffset = frameSize
    val vOffset = frameSize + frameSize / 4
    val halfWidth = width / 2
    var i = 0
    for (row in 0 until height) {
      val uvRow = (row / 2) * halfWidth
      for (col in 0 until width) {
        val uvIndex = uvRow + col / 2
        val y = ((yuv[i].toInt() and 0xFF) - 16).coerceAtLeast(0) * 1192 // 1.164 * 1024
        val u = (yuv[uOffset + uvIndex].toInt() and 0xFF) - 128
        val v = (yuv[vOffset + uvIndex].toInt() and 0xFF) - 128
        val r = ((y + 1836 * v) shr 10).coerceIn(0, 255)
        val g = ((y - 218 * u - 546 * v) shr 10).coerceIn(0, 255)
        val b = ((y + 2163 * u) shr 10).coerceIn(0, 255)
        out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        i++
      }
    }
    return out
  }

  /** Copies the frame out of the SDK buffer without moving its position; null if malformed. */
  fun copy(buffer: ByteBuffer, width: Int, height: Int): ByteArray? {
    if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) return null
    val needed = width * height * 3 / 2
    if (buffer.remaining() < needed) return null
    return ByteArray(needed).also { buffer.duplicate().get(it) }
  }

  fun toBitmap(yuv: ByteArray, width: Int, height: Int): Bitmap =
      Bitmap.createBitmap(toArgb(yuv, width, height), width, height, Bitmap.Config.ARGB_8888)
}
