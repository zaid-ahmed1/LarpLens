package com.larplens.camera

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Runs the OpenCV detector on synthetic frames and on real name tag photos at glasses-stream resolution (504 px wide), upright
 * and tilted, plus tag-free regions that must stay silent.
 *
 * The photos are of real people, so they live in the git-ignored `testdata/tags/` folder (as
 * 504-px-wide 24-bit BMPs, made with `sips --resampleWidth 504 -s format bmp`) and this test is
 * skipped when it's missing. Annotated results are written to `app/build/tag-eval/`.
 *
 * (Android unit tests compile against android.jar, which has no java.awt / ImageIO, hence the
 * small BMP reader/writer below.)
 */
class OpenCvTagDetectorTest {
  companion object {
    /** Loads the desktop OpenCV natives (org.openpnp:opencv) for the JVM test run. */
    @BeforeClass
    @JvmStatic
    fun loadOpenCv() {
      nu.pattern.OpenCV.loadLocally()
    }
  }

  private val dir = File("../testdata/tags")
  private val out = File("build/tag-eval").apply { mkdirs() }
  private val detector = OpenCvTagDetector()

  // ---- Synthetic checks (always run) ----

  /** Gray frame with an optional white rounded tag; [text] adds dark letter-like strokes. */
  private fun synthetic(
      tag: Boolean,
      text: Boolean = true,
      x0: Int = 150,
      y0: Int = 380,
      ink: Int = 0x202020,
  ): Rgb {
    val img = Rgb(504, 896)
    img.px.fill(0x5A5A5A)
    if (tag) {
      val x1 = x0 + 200
      val y1 = y0 + 120
      val r = 14
      for (y in y0 until y1) for (x in x0 until x1) {
        val cx = x.coerceIn(x0 + r, x1 - r)
        val cy = y.coerceIn(y0 + r, y1 - r)
        if (hypot((x - cx).toDouble(), (y - cy).toDouble()) <= r) img.px[y * 504 + x] = 0xEEEEEE
      }
      if (text) {
        for (k in 0 until 6) for (y in y0 + 35 until y1 - 35) for (dx in 0 until 7) {
          img.px[y * 504 + x0 + 30 + k * 26 + dx] = ink
        }
      }
    }
    return img
  }

  @Test
  fun syntheticRoundedTagFound() {
    val d = detect(synthetic(tag = true))
    assertNotNull(d)
    assertTrue("box ${d!!.box}", d.box.left in 0.27f..0.33f && d.box.top in 0.40f..0.45f)
  }

  @Test
  fun syntheticRedMarkerFound() {
    // Classic red marker, and a pale pink-red that is too light to count as "dark" ink: only the
    // colored-ink check finds it (verified: a darkness-only check misses this one)
    assertNotNull("red", detect(synthetic(tag = true, ink = 0xD0242C)))
    assertNotNull("pale pink-red", detect(synthetic(tag = true, ink = 0xF59AA0)))
  }

  @Test
  fun syntheticBlackMarkerFound() {
    assertNotNull(detect(synthetic(tag = true, ink = 0x151515)))
  }

  /** A white rectangle w x h at (x0, y0) with a few dark strokes, e.g. a window or poster. */
  private fun whiteBox(x0: Int, y0: Int, w: Int, h: Int): Rgb {
    val img = Rgb(504, 896)
    img.px.fill(0x5A5A5A)
    for (y in y0 until y0 + h) for (x in x0 until x0 + w) img.px[y * 504 + x] = 0xEEEEEE
    for (k in 0 until 4) for (y in y0 + h / 4 until y0 + 3 * h / 4) for (dx in 0 until 7) {
      img.px[y * 504 + x0 + w / 5 + k * w / 6 + dx] = 0x202020
    }
    return img
  }

  @Test
  fun portraitWindowOrPosterIgnored() {
    assertNull("tall poster", detect(whiteBox(180, 250, 150, 300)))
    assertNull("square window", detect(whiteBox(160, 330, 190, 190)))
  }

  @Test
  fun landscapeBoxOfTheSameSizeFound() {
    assertNotNull(detect(whiteBox(110, 370, 280, 150)))
  }

  @Test
  fun syntheticBlankWhiteShapeIgnored() {
    assertNull(detect(synthetic(tag = true, text = false)))
  }

  @Test
  fun syntheticTagCutOffByFrameEdgeIgnored() {
    assertNull(detect(synthetic(tag = true, x0 = 0)))
  }

  @Test
  fun syntheticEmptyFrame() {
    assertNull(detect(synthetic(tag = false)))
  }

  // ---- Real photos (skipped without testdata/tags) ----

  /** Hand-labeled tag bounds (normalized left, top, right, bottom) for each photo. */
  private val expected =
      mapOf(
          "PXL_20261101_190613287" to NormRect(0.375f, 0.39f, 0.69f, 0.53f), // Zaid, brown
          "PXL_20261101_190636379" to NormRect(0.29f, 0.49f, 0.60f, 0.59f), // Shayan, peeling
          "PXL_20261101_190645962" to NormRect(0.25f, 0.35f, 0.73f, 0.60f), // Aayan, oatmeal
          "PXL_20261101_190700847" to NormRect(0.19f, 0.33f, 0.64f, 0.58f), // Victor, tilted
          "PXL_20261101_190706062" to NormRect(0.15f, 0.24f, 0.44f, 0.36f), // Mishe, blue
          "PXL_20261101_190710936" to NormRect(0.39f, 0.33f, 0.76f, 0.51f), // Andrey, red ink
          "PXL_20261101_190723646" to NormRect(0.25f, 0.39f, 0.66f, 0.54f), // Zaid, beige
          "PXL_20261101_190728705" to NormRect(0.28f, 0.48f, 0.65f, 0.65f), // Zaid, beige
          "PXL_20261101_190733811" to NormRect(0.45f, 0.44f, 0.71f, 0.58f), // Zaid, beige, far
      )

  /** Regions with no tag that must stay silent: (photo, top fraction, bottom fraction). */
  private val negatives =
      listOf(
          Triple("PXL_20261101_190636379", 0.0f, 0.45f), // white "ENDLESS PEAKS" print
          Triple("PXL_20261101_190613287", 0.56f, 1.0f), // plain brown sweater
          Triple("PXL_20261101_190723646", 0.58f, 1.0f), // beige hoodie
          Triple("PXL_20261101_190706062", 0.40f, 1.0f), // blue sweater, khaki pants
          Triple("PXL_20261101_190645962", 0.62f, 1.0f), // oatmeal sleeve, chair, wall
      )

  @Test
  fun findsEveryTagUprightAndTilted() {
    assumeTrue("no testdata/tags photos; skipping", dir.isDirectory)
    val failures = mutableListOf<String>()
    var runs = 0
    for ((name, box) in expected) {
      val photo = Rgb.readBmp(File(dir, "$name.bmp"))
      for (angle in listOf(0.0, -15.0, 15.0, -30.0, 30.0)) {
        runs++
        val image = photo.rotate(angle)
        val started = System.nanoTime()
        val d = detect(image)
        val ms = (System.nanoTime() - started) / 1_000_000
        val (ex, ey) =
            rotatePoint((box.left + box.right) / 2, (box.top + box.bottom) / 2, angle, image)
        val ok =
            d != null &&
                hypot((d.box.left + d.box.right) / 2 - ex, (d.box.top + d.box.bottom) / 2 - ey) < 0.08f
        val label = "$name @ ${angle.toInt()}°"
        println(String.format("%-30s %4d ms  %s", label, ms, describe(d, ok)))
        image.annotate(d, ok).writeBmp(File(out, "${name}_${angle.toInt()}.bmp"))
        if (!ok) {
          failures += label
          detector.trace = { println("      $it") }
          detect(image)
          detector.trace = null
        }
      }
    }
    println("tags found: ${runs - failures.size}/$runs")
    assertTrue("missed: $failures", failures.isEmpty())
  }

  @Test
  fun staysSilentWhereThereIsNoTag() {
    assumeTrue("no testdata/tags photos; skipping", dir.isDirectory)
    val falseHits = mutableListOf<String>()
    for ((name, top, bottom) in negatives) {
      val image = Rgb.readBmp(File(dir, "$name.bmp")).cropRows(top, bottom)
      val d = detect(image)
      val label = "$name [$top-$bottom]"
      println(String.format("%-30s %s", label, if (d == null) "silent ok" else "FALSE HIT ${describe(d, false)}"))
      image.annotate(d, d == null).writeBmp(File(out, "neg_${name}_$top.bmp"))
      if (d != null) falseHits += label
    }
    assertTrue("false hits: $falseHits", falseHits.isEmpty())
  }

  // ---- helpers ----

  private fun describe(d: TagDetection?, ok: Boolean) =
      if (d == null) "MISS (nothing found)"
      else
          String.format(
              "%s fill=%.2f ink=%.2f box=(%.2f,%.2f)-(%.2f,%.2f)",
              if (ok) "ok   " else "WRONG",
              d.fill,
              d.ink,
              d.box.left,
              d.box.top,
              d.box.right,
              d.box.bottom,
          )

  private fun detect(image: Rgb): TagDetection? = detector.detect(image.toI420(), image.w, image.h)

  /** Where a normalized point lands after [Rgb.rotate] (aspect-correct). */
  private fun rotatePoint(x: Float, y: Float, degrees: Double, img: Rgb): Pair<Float, Float> {
    if (degrees == 0.0) return x to y
    val a = Math.toRadians(degrees)
    val dx = (x - 0.5) * img.w
    val dy = (y - 0.5) * img.h
    val rx = dx * cos(a) - dy * sin(a)
    val ry = dx * sin(a) + dy * cos(a)
    return (0.5 + rx / img.w).toFloat() to (0.5 + ry / img.h).toFloat()
  }

  /** Minimal RGB image: pixels are 0xRRGGBB. */
  private class Rgb(val w: Int, val h: Int, val px: IntArray = IntArray(w * h)) {

    /** Rotates around the center (clockwise for positive degrees, y down); corners mid-gray. */
    fun rotate(degrees: Double): Rgb {
      if (degrees == 0.0) return this
      val a = Math.toRadians(degrees)
      val c = cos(a)
      val s = sin(a)
      val cx = w / 2.0
      val cy = h / 2.0
      val dst = Rgb(w, h)
      for (y in 0 until h) for (x in 0 until w) {
        // inverse map: destination -> source
        val dx = x - cx
        val dy = y - cy
        val sx = dx * c + dy * s + cx
        val sy = -dx * s + dy * c + cy
        dst.px[y * w + x] = sample(sx, sy)
      }
      return dst
    }

    private fun sample(x: Double, y: Double): Int {
      if (x < 0 || y < 0 || x > w - 1 || y > h - 1) return 0x646464
      val x0 = floor(x).toInt().coerceAtMost(w - 2)
      val y0 = floor(y).toInt().coerceAtMost(h - 2)
      val fx = x - x0
      val fy = y - y0
      var result = 0
      for (shift in intArrayOf(16, 8, 0)) {
        fun ch(xx: Int, yy: Int) = (px[yy * w + xx] shr shift) and 0xFF
        val top = ch(x0, y0) * (1 - fx) + ch(x0 + 1, y0) * fx
        val bottom = ch(x0, y0 + 1) * (1 - fx) + ch(x0 + 1, y0 + 1) * fx
        result = result or (((top * (1 - fy) + bottom * fy).toInt() and 0xFF) shl shift)
      }
      return result
    }

    fun cropRows(top: Float, bottom: Float): Rgb {
      val y0 = ((h * top).toInt() / 2) * 2
      val y1 = ((h * bottom).toInt() / 2) * 2
      return Rgb(w, y1 - y0, px.copyOfRange(y0 * w, y1 * w))
    }

    /** RGB -> I420 with BT.709 limited range (what the glasses' YUV frames use). */
    fun toI420(): ByteArray {
      val out = ByteArray(w * h * 3 / 2)
      val uBase = w * h
      val vBase = uBase + w * h / 4
      for (i in 0 until w * h) {
        val p = px[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        out[i] = (16 + 0.1826 * r + 0.6142 * g + 0.0620 * b).toInt().coerceIn(0, 255).toByte()
      }
      for (y in 0 until h / 2) for (x in 0 until w / 2) {
        var u = 0.0
        var v = 0.0
        for (dy in 0..1) for (dx in 0..1) {
          val p = px[(2 * y + dy) * w + 2 * x + dx]
          val r = (p shr 16) and 0xFF
          val g = (p shr 8) and 0xFF
          val b = p and 0xFF
          u += 128 - 0.1006 * r - 0.3386 * g + 0.4392 * b
          v += 128 + 0.4392 * r - 0.3989 * g - 0.0403 * b
        }
        out[uBase + y * (w / 2) + x] = (u / 4).toInt().coerceIn(0, 255).toByte()
        out[vBase + y * (w / 2) + x] = (v / 4).toInt().coerceIn(0, 255).toByte()
      }
      return out
    }

    /** Copy with the detection outline drawn: green if right, red if wrong. */
    fun annotate(d: TagDetection?, ok: Boolean): Rgb {
      val copy = Rgb(w, h, px.copyOf())
      val color = if (ok) 0x2ECC71 else 0xE74C3C
      if (d != null) {
        val b = d.box
        copy.line(b.left * w, b.top * h, b.right * w, b.top * h, color)
        copy.line(b.right * w, b.top * h, b.right * w, b.bottom * h, color)
        copy.line(b.right * w, b.bottom * h, b.left * w, b.bottom * h, color)
        copy.line(b.left * w, b.bottom * h, b.left * w, b.top * h, color)
      } else if (!ok) {
        for (y in 0 until 12) for (x in 0 until 12) copy.px[y * w + x] = color
      }
      return copy
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
      val steps = max(abs(x1 - x0), abs(y1 - y0)).toInt().coerceAtLeast(1)
      for (i in 0..steps) {
        val x = (x0 + (x1 - x0) * i / steps).toInt()
        val y = (y0 + (y1 - y0) * i / steps).toInt()
        for (oy in -1..1) for (ox in -1..1) {
          val xx = x + ox
          val yy = y + oy
          if (xx in 0 until w && yy in 0 until h) px[yy * w + xx] = color
        }
      }
    }

    fun writeBmp(file: File) {
      val rowSize = (w * 3 + 3) / 4 * 4
      val buf = ByteBuffer.allocate(54 + rowSize * h).order(ByteOrder.LITTLE_ENDIAN)
      buf.put('B'.code.toByte()).put('M'.code.toByte()).putInt(54 + rowSize * h).putInt(0).putInt(54)
      buf.putInt(40).putInt(w).putInt(-h).putShort(1).putShort(24).putInt(0).putInt(rowSize * h)
      buf.putInt(2835).putInt(2835).putInt(0).putInt(0)
      for (y in 0 until h) {
        for (x in 0 until w) {
          val p = px[y * w + x]
          buf.put((p and 0xFF).toByte()).put(((p shr 8) and 0xFF).toByte()).put(((p shr 16) and 0xFF).toByte())
        }
        repeat(rowSize - w * 3) { buf.put(0) }
      }
      file.writeBytes(buf.array())
    }

    companion object {
      /** Reads an uncompressed 24- or 32-bit BMP (top-down or bottom-up), even dimensions. */
      fun readBmp(file: File): Rgb {
        val bytes = file.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) { "not a BMP: $file" }
        val offset = bb.getInt(10)
        val width = bb.getInt(18)
        val rawHeight = bb.getInt(22)
        val bpp = bb.getShort(28).toInt()
        require(bb.getInt(30) == 0 || bb.getInt(30) == 3) { "compressed BMP: $file" }
        val height = abs(rawHeight)
        val topDown = rawHeight < 0
        val bytesPerPixel = bpp / 8
        val rowSize = (width * bytesPerPixel + 3) / 4 * 4
        val w = width / 2 * 2
        val h = height / 2 * 2
        val img = Rgb(w, h)
        for (y in 0 until h) {
          val srcRow = if (topDown) y else height - 1 - y
          val base = offset + srcRow * rowSize
          for (x in 0 until w) {
            val i = base + x * bytesPerPixel
            val b = bytes[i].toInt() and 0xFF
            val g = bytes[i + 1].toInt() and 0xFF
            val r = bytes[i + 2].toInt() and 0xFF
            img.px[y * w + x] = (r shl 16) or (g shl 8) or b
          }
        }
        return img
      }
    }
  }
}
