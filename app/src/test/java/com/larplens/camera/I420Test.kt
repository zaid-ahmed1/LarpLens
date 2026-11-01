package com.larplens.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class I420Test {

  private fun solid(y: Int, u: Int, v: Int, w: Int = 4, h: Int = 2): ByteArray {
    val frame = w * h
    return ByteArray(frame * 3 / 2) { i ->
      when {
        i < frame -> y
        i < frame + frame / 4 -> u
        else -> v
      }.toByte()
    }
  }

  private fun rgb(argb: Int) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

  @Test
  fun blackAndWhiteMapToExtremes() {
    assertEquals(Triple(0, 0, 0), rgb(I420.toArgb(solid(16, 128, 128), 4, 2)[0]))
    val (r, g, b) = rgb(I420.toArgb(solid(235, 128, 128), 4, 2)[0])
    assertTrue("white should be ~255, was $r,$g,$b", r >= 253 && g >= 253 && b >= 253)
  }

  @Test
  fun highVIsRedHighUIsBlue() {
    val (r, _, b) = rgb(I420.toArgb(solid(81, 90, 240), 4, 2)[0])
    assertTrue("expected red-dominant, was r=$r b=$b", r > 200 && b < 60)
    val (r2, _, b2) = rgb(I420.toArgb(solid(41, 240, 110), 4, 2)[0])
    assertTrue("expected blue-dominant, was r=$r2 b=$b2", b2 > 200 && r2 < 60)
  }

  @Test
  fun alphaIsOpaque() {
    I420.toArgb(solid(100, 128, 128), 4, 2).forEach { assertEquals(0xFF, it ushr 24) }
  }

  @Test(expected = IllegalArgumentException::class)
  fun oddDimensionsRejected() {
    I420.toArgb(ByteArray(100), 3, 2)
  }
}
