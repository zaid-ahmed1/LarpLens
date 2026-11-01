package com.larplens.camera

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Finds a white, rounded-rectangle name tag with OpenCV.
 *
 * 1. I420 -> RGB -> HSV at half resolution.
 * 2. White = low saturation and bright. Brightness cutoffs adapt to the frame (Otsu, plus a
 *    stricter one so an off-white/beige shirt doesn't fuse with the tag).
 * 3. Morphological close (fills the handwriting, bridges small curls/peels), then open (cuts thin
 *    bridges to nearby white stuff).
 * 4. External contours. A tag is a contour that is:
 *    - fully in frame and a plausible size,
 *    - convex-ish (solidity vs its convex hull), so curled corners are tolerated but irregular
 *      white shapes (prints, folds of a white shirt) are not,
 *    - fills its *rotated* minimum-area rectangle well (rounded corners and tilt are fine),
 *    - not a sliver, and
 *    - has a little ink inside (writing), so a blank wall or plain paper isn't a tag.
 * 5. Best = most rectangular x biggest x most central.
 *
 * Call [org.opencv.android.OpenCVLoader.initLocal] once before use on Android.
 */
class OpenCvTagDetector(
    private val maxSaturation: Double = 60.0,
    private val minBrightness: Double = 120.0,
    private val minAreaFrac: Double = 0.003,
    private val maxAreaFrac: Double = 0.5,
    private val minSolidity: Double = 0.80,
    private val minRectFill: Double = 0.72,
    private val maxElongation: Double = 4.5,
    /** Width must be at least this many times the height (on the rotated rectangle). */
    private val minAspect: Double = 1.2,
    /** The long side may tilt at most this far from horizontal. */
    private val maxTiltDegrees: Double = 55.0,
    private val minInk: Double = 0.01,
    private val maxInk: Double = 0.45,
    /** Saturation (0-255) above which a pixel counts as colored ink; white paper is ~5-15. */
    private val minInkSaturation: Double = 90.0,
) : TagDetector {

  /** Debug hook: when set, each candidate contour's measurements and verdict are reported. */
  var trace: ((String) -> Unit)? = null

  override fun detect(yuv: ByteArray, width: Int, height: Int): TagDetection? {
    val mats = mutableListOf<Mat>()
    fun <T : Mat> T.track(): T = also { mats += it }
    try {
      val yuvMat = Mat(height * 3 / 2, width, CvType.CV_8UC1).track()
      yuvMat.put(0, 0, yuv)
      val rgbFull = Mat().track()
      Imgproc.cvtColor(yuvMat, rgbFull, Imgproc.COLOR_YUV2RGB_I420)
      val rgb = Mat().track()
      Imgproc.resize(rgbFull, rgb, Size(width / 2.0, height / 2.0), 0.0, 0.0, Imgproc.INTER_AREA)
      val w = rgb.cols()
      val h = rgb.rows()
      val frameArea = (w * h).toDouble()

      val hsv = Mat().track()
      Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
      val channels = ArrayList<Mat>(3)
      Core.split(hsv, channels)
      channels.forEach { it.track() }
      // Smoothed so fabric texture (speckles of low saturation) doesn't leak into "white"
      val sat = Mat().track()
      Imgproc.GaussianBlur(channels[1], sat, Size(7.0, 7.0), 0.0)
      val value = channels[2]
      val gray = Mat().track()
      Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)

      val otsuScratch = Mat().track()
      val otsu = Imgproc.threshold(value, otsuScratch, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
      val base = max(minBrightness, otsu)
      // Loose cutoffs keep shaded/peeling tags on dark clothes whole; strict ones separate the
      // tag from beige/oatmeal fabric (also bright and fairly colorless). Each try is ~1 ms.
      val brightnessCutoffs = listOf(base, base + (255 - base) * 0.35, base + (255 - base) * 0.6)
      // The strictest pass catches a tag on near-white fabric: paper measured S ~7-10 vs an
      // oatmeal sweater at 14-30 (0-255 scale), at the same brightness
      val saturationCutoffs = listOf(maxSaturation, maxSaturation / 2, 11.0)

      // Candidate "white" masks
      val masks = mutableListOf<Pair<String, Mat>>()
      val lowSatLoose = Mat().track()
      Core.inRange(sat, Scalar(0.0), Scalar(maxSaturation), lowSatLoose)
      for (satCutoff in saturationCutoffs) {
        val lowSat = Mat().track()
        Core.inRange(sat, Scalar(0.0), Scalar(satCutoff), lowSat)
        for (cutoff in brightnessCutoffs) {
          val bright = Mat().track()
          Imgproc.threshold(value, bright, cutoff, 255.0, Imgproc.THRESH_BINARY)
          val mask = Mat().track()
          Core.bitwise_and(bright, lowSat, mask)
          masks += "s<=${satCutoff.toInt()} v>=${cutoff.toInt()}" to mask
        }
      }
      // Locally brighter than the surroundings: separates a tag from near-white fabric (oatmeal,
      // cream) that a global cutoff can't. The neighborhood must be bigger than the tag, or the
      // tag's own interior becomes the "local average".
      val brightEnough = Mat().track()
      Imgproc.threshold(value, brightEnough, minBrightness, 255.0, Imgproc.THRESH_BINARY)
      for (blockFrac in listOf(0.5, 0.95)) for (offset in listOf(6.0, 12.0)) {
        val block = ((min(w, h) * blockFrac).toInt() / 2) * 2 + 1
        val local = Mat().track()
        Imgproc.adaptiveThreshold(
            value, local, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY, block, -offset)
        val mask = Mat().track()
        Core.bitwise_and(local, lowSatLoose, mask)
        Core.bitwise_and(mask, brightEnough, mask)
        masks += "local$block+${offset.toInt()}" to mask
      }

      // Small close keeps a tag separate from nearby white; large close fills writing that
      // reaches the edge of a small/far tag
      val closeKernels =
          listOf(7.0, 11.0).map { Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(it, it)).track() }
      val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0)).track()

      var best: TagDetection? = null
      for ((name, raw) in masks) for ((k, closeKernel) in closeKernels.withIndex()) {
        val mask = Mat().track()
        Imgproc.morphologyEx(raw, mask, Imgproc.MORPH_CLOSE, closeKernel)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, openKernel)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat().track()
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        for (contour in contours) {
          contour.track()
          val candidate =
              evaluate(contour, gray, channels[1], w, h, frameArea, mats, "$name close${if (k == 0) 7 else 11}")
                  ?: continue
          if (best == null || candidate.score > best.score) best = candidate
        }
      }
      return best
    } finally {
      mats.forEach { it.release() }
    }
  }

  private fun evaluate(
      contour: MatOfPoint,
      gray: Mat,
      /** Unsmoothed saturation, so thin colored pen strokes keep their color. */
      saturation: Mat,
      w: Int,
      h: Int,
      frameArea: Double,
      mats: MutableList<Mat>,
      pass: String = "",
  ): TagDetection? {
    val area = Imgproc.contourArea(contour)
    val areaFrac = area / frameArea
    if (areaFrac < minAreaFrac || areaFrac > maxAreaFrac) return null

    // Fully in view: touching the edge means a wall, a shirt, or a cut-off tag
    val bounds = Imgproc.boundingRect(contour)
    fun reject(why: String): TagDetection? {
      trace?.invoke("$pass area=%.3f bbox=(${bounds.x},${bounds.y},${bounds.width}x${bounds.height}) REJECT $why".format(areaFrac))
      return null
    }
    if (bounds.x <= 1 || bounds.y <= 1 || bounds.x + bounds.width >= w - 1 || bounds.y + bounds.height >= h - 1) {
      return reject("touches edge")
    }

    // Convex-ish: curled corners cost a little, irregular shapes cost a lot
    val hullIdx = MatOfInt().also { mats += it }
    Imgproc.convexHull(contour, hullIdx)
    val points = contour.toArray()
    val hull = MatOfPoint(*hullIdx.toArray().map { points[it] }.toTypedArray()).also { mats += it }
    val hullArea = Imgproc.contourArea(hull)
    if (hullArea <= 0.0) return null
    val solidity = area / hullArea
    if (solidity < minSolidity) return reject("solidity=%.2f".format(solidity))

    // Rounded rectangle at any angle: fills its minimum-area rotated rectangle
    val contour2f = MatOfPoint2f(*points).also { mats += it }
    val rotated = Imgproc.minAreaRect(contour2f)
    val rectArea = rotated.size.width * rotated.size.height
    if (rectArea <= 0.0) return null
    val rectFill = area / rectArea
    val longSide = max(rotated.size.width, rotated.size.height)
    val shortSide = min(rotated.size.width, rotated.size.height)
    if (rectFill < minRectFill || longSide / shortSide > maxElongation) {
      return reject("rectFill=%.2f elong=%.1f".format(rectFill, longSide / shortSide))
    }

    // Landscape only: a name tag is wider than it is tall. Measured on the rotated rectangle so a
    // slightly tilted tag still counts; portrait windows, doors and posters (and squares) don't.
    val corners = arrayOfNulls<Point>(4).also { rotated.points(it) }.map { it!! }
    val edgeA = Point(corners[1].x - corners[0].x, corners[1].y - corners[0].y)
    val edgeB = Point(corners[2].x - corners[1].x, corners[2].y - corners[1].y)
    val longEdge = if (hypot(edgeA.x, edgeA.y) >= hypot(edgeB.x, edgeB.y)) edgeA else edgeB
    val tiltDegrees = Math.toDegrees(atan2(abs(longEdge.y), abs(longEdge.x)))
    if (longSide / shortSide < minAspect || tiltDegrees > maxTiltDegrees) {
      return reject("not landscape: aspect=%.2f tilt=%.0f°".format(longSide / shortSide, tiltDegrees))
    }

    // Some writing inside: pixels that are dark (black/blue pen) OR clearly colored (red marker,
    // which can be nearly as light as the paper in grayscale) within the filled contour
    val region = Mat.zeros(h, w, CvType.CV_8UC1).also { mats += it }
    Imgproc.drawContours(region, listOf(contour), 0, Scalar(255.0), -1)
    val paper = Core.mean(gray, region).`val`[0]
    val dark = Mat().also { mats += it }
    Imgproc.threshold(gray, dark, paper * 0.65, 255.0, Imgproc.THRESH_BINARY_INV)
    val colored = Mat().also { mats += it }
    Imgproc.threshold(saturation, colored, minInkSaturation, 255.0, Imgproc.THRESH_BINARY)
    Core.bitwise_or(dark, colored, dark)
    Core.bitwise_and(dark, region, dark)
    val ink = Core.countNonZero(dark) / max(1.0, Core.countNonZero(region).toDouble())
    if (ink < minInk || ink > maxInk) return reject("ink=%.3f paper=%.0f".format(ink, paper))

    val cx = (bounds.x + bounds.width / 2.0) / w
    val cy = (bounds.y + bounds.height / 2.0) / h
    val centrality = 1.0 - 0.5 * (hypot(cx - 0.5, cy - 0.5) / 0.7071)
    val score = rectFill * solidity * sqrt(areaFrac) * centrality
    return TagDetection(
        box =
            NormRect(
                bounds.x.toFloat() / w,
                bounds.y.toFloat() / h,
                (bounds.x + bounds.width).toFloat() / w,
                (bounds.y + bounds.height).toFloat() / h,
            ),
        score = score.toFloat(),
        fill = rectFill.toFloat(),
        ink = ink.toFloat(),
    )
  }
}
